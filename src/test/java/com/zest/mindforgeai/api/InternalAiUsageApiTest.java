package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.properties.InternalApiProperties;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部接口：接收 Python 上报的 AI 使用量。
 *
 * <p>核心语义是 <b>fire-and-forget</b> —— 上报方不关心落库结果，
 * 所以这个接口永远返回成功。用例里要守住这一点：脏数据也不能让它报错。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("内部接口 - AI 使用量")
class InternalAiUsageApiTest extends AbstractApiTest {

    private static final String PATH = "/internal/ai-usage";

    @Autowired
    private InternalApiProperties internalApiProperties;

    private ApiResult report(Object body, String serviceToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (serviceToken != null) {
            headers.set("X-Service-Token", serviceToken);
        }
        ResponseEntity<JsonNode> response = rest.exchange(
                PATH, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        return new ApiResult(response.getStatusCode().value(), response.getBody());
    }

    private ApiResult report(Object body) {
        return report(body, internalApiProperties.getServiceToken());
    }

    /** 一条完整的上报报文。 */
    private Map<String, Object> payload() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", "2");
        body.put("requestId", "trace-abc-123");
        body.put("scene", "RESUME_PARSE");
        body.put("provider", "dashscope");
        body.put("modelName", "qwen3.8-27b");
        body.put("promptTokens", 1200);
        body.put("completionTokens", 800);
        body.put("totalTokens", 2000);
        body.put("durationMs", 85000L);
        body.put("status", "SUCCESS");
        return body;
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record", Integer.class);
    }

    // ---------- 鉴权 ----------

    @Test
    @DisplayName("不带服务令牌 → 401")
    void requiresServiceToken() {
        report(payload(), null).assertHttpStatus(401);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("服务令牌不对 → 401")
    void rejectsWrongServiceToken() {
        report(payload(), "nope").assertHttpStatus(401);
    }

    // ---------- 正常写入 ----------

    @Test
    @DisplayName("上报成功：字段原样落库")
    void recordsUsage() {
        report(payload()).assertSuccess();

        assertThat(rowCount()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_usage_record");
        assertThat(row.get("user_id")).isEqualTo(2L);
        assertThat(row.get("request_id")).isEqualTo("trace-abc-123");
        assertThat(row.get("scene")).isEqualTo("RESUME_PARSE");
        assertThat(row.get("provider")).isEqualTo("dashscope");
        assertThat(row.get("model_name")).isEqualTo("qwen3.8-27b");
        assertThat(row.get("prompt_tokens")).isEqualTo(1200);
        assertThat(row.get("completion_tokens")).isEqualTo(800);
        assertThat(row.get("total_tokens")).isEqualTo(2000);
        assertThat(row.get("duration_ms")).isEqualTo(85000L);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("可以连续上报多条")
    void recordsMultiple() {
        report(payload()).assertSuccess();
        report(payload()).assertSuccess();
        report(payload()).assertSuccess();

        assertThat(rowCount()).isEqualTo(3);
    }

    // ---------- 容错：脏数据不能让它报错 ----------

    @Test
    @DisplayName("缺 scene → 40001（这是唯一硬性必填的）")
    void sceneIsRequired() {
        Map<String, Object> body = payload();
        body.remove("scene");

        report(body).assertCode(ErrorCode.PARAM_INVALID.getCode());
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("缺 status → 40001")
    void statusIsRequired() {
        Map<String, Object> body = payload();
        body.remove("status");

        report(body).assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("★ status 是乱七八糟的值 → 归一成 FAILED，不报错")
    void unknownStatusIsNormalizedToFailed() {
        Map<String, Object> body = payload();
        body.put("status", "有点奇怪");

        report(body).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT status FROM ai_usage_record", String.class))
                .as("只认 SUCCESS，其余一律 FAILED —— 否则统计按 status 分组会散成好几组")
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("★ 不传 totalTokens → 用 prompt + completion 自动补")
    void totalTokensIsComputedWhenMissing() {
        Map<String, Object> body = payload();
        body.remove("totalTokens");

        report(body).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT total_tokens FROM ai_usage_record", Integer.class))
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("★ userId 不是数字 → 记 null，但记录照写")
    void nonNumericUserIdDoesNotDropTheRecord() {
        Map<String, Object> body = payload();
        body.put("userId", "not-a-number");

        report(body).assertSuccess();

        assertThat(rowCount()).as("userId 只是附加信息，不该因为它是脏的就把整条账丢了").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM ai_usage_record", Long.class)).isNull();
    }

    @Test
    @DisplayName("★ 超长字段 → 截断后落库，不报错")
    void overlongFieldsAreTruncated() {
        Map<String, Object> body = payload();
        body.put("scene", "X".repeat(200));
        body.put("errorMessage", "E".repeat(5000));

        report(body).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(scene) FROM ai_usage_record", Integer.class))
                .as("scene 列是 varchar(64)").isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(error_message) FROM ai_usage_record", Integer.class))
                .as("error_message 列是 varchar(1000)").isEqualTo(1000);
    }

    @Test
    @DisplayName("token 传负数 → 归零（不写进去一个负的用量）")
    void negativeTokensBecomeZero() {
        Map<String, Object> body = payload();
        body.put("promptTokens", -5);
        body.put("completionTokens", -1);
        body.remove("totalTokens");

        report(body).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT prompt_tokens FROM ai_usage_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT total_tokens FROM ai_usage_record", Integer.class)).isZero();
    }

    @Test
    @DisplayName("失败的上报也能记（status=FAILED + 原因）")
    void failureIsRecorded() {
        Map<String, Object> body = payload();
        body.put("status", "FAILED");
        body.put("errorMessage", "AuthenticationError: 401 Incorrect API key");

        report(body).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT status FROM ai_usage_record", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM ai_usage_record", String.class))
                .contains("Incorrect API key");
    }
}
