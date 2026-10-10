package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.support.AbstractApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式对话接口「开流之前」的错误响应。
 * <p>
 * 前端按 SSE 约定发 {@code Accept: text/event-stream}（见 {@code apps/user/src/api/chat.ts}）。
 * 如果错误响应只按默认内容协商写 JSON，Jackson 转换器会被判「不可接受」，
 * 客户端拿到的是 <b>500</b> 而不是本该有的 404 —— 前端只能提示「连接失败（HTTP 500）」。
 * 这里锁住：开流前的错误仍是 JSON + 真实状态码。
 */
class SseErrorResponseTest extends AbstractApiTest {

    @Test
    @DisplayName("Accept: text/event-stream 时，开流前的 404 仍是 JSON + 404（不是 500）")
    void preStreamError_staysJsonDespiteSseAccept() {
        String token = loginAsUser();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        headers.setBearerAuth(token);

        ResponseEntity<JsonNode> resp = rest.exchange(
                "/app/conversations/999999/messages/stream",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("message", "你好"), headers),
                JsonNode.class);

        assertThat(resp.getStatusCode().value())
                .as("开流前的错误必须返回真实状态码，而不是内容协商失败导致的 500")
                .isEqualTo(404);
        assertThat(resp.getHeaders().getContentType())
                .as("错误体固定为 JSON")
                .isNotNull();
        assertThat(resp.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().path("code").asInt()).isEqualTo(40406);
    }
}
