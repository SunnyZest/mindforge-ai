package com.zest.mindforgeai.handler;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 兜底异常处理的响应已提交分支。
 * <p>
 * 这条分支专门对付「SSE / 流式接口开始推送后客户端断开」：此时容器把裸 {@code IOException}
 * 抛到 MVC，响应已经 committed，再写响应体必然二次抛错（{@code HttpMessageNotWritableException}），
 * 日志里就是一条唬人的 ERROR。这里锁住「已提交 → 不写 body」的行为。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(mock(AuditService.class));

    @Test
    @DisplayName("响应已提交时兜底不写响应体（返回 null）—— 避免二次抛错刷 ERROR")
    void handleOther_committedResponse_skipsBody() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/app/conversations/1/messages/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        ResponseEntity<Result<Void>> result =
                handler.handleOther(new IOException("你的主机中的软件中止了一个已建立的连接。"), request, response);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("响应未提交时兜底仍返回 500 + 统一信封")
    void handleOther_notCommitted_returnsEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Result<Void>> result =
                handler.handleOther(new RuntimeException("boom"), request, response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
    }
}
