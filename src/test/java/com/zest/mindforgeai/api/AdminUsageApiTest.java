package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端：AI 使用量统计。
 *
 * <p>数据是直接插进 {@code ai_usage_record} 的（不走内部接口）——
 * 这样能精确控制 created_at / scene / model，把聚合逻辑单独测清楚。
 * 写入路径由 {@code InternalAiUsageApiTest} 覆盖。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("管理端 - AI 使用量")
class AdminUsageApiTest extends AbstractApiTest {

    private static final String PATH = "/admin/usage";

    private void insertUsage(String scene, String model, String status,
                             int prompt, int completion, int daysAgo, Long userId) {
        jdbc.update("""
                INSERT INTO ai_usage_record
                    (user_id, request_id, scene, provider, model_name,
                     prompt_tokens, completion_tokens, total_tokens,
                     duration_ms, status, created_at)
                VALUES (?, 'trace-x', ?, 'dashscope', ?, ?, ?, ?, 1000, ?,
                        DATE_SUB(NOW(), INTERVAL ? DAY))
                """, userId, scene, model, prompt, completion, prompt + completion, status, daysAgo);
    }

    /** ⚠️ 名字不能叫 get —— 会和基类的 protected get(path, token) 撞签名，
     *  编译器会报「访问权限更弱」。 */
    private ApiResult usage(String query, String token) {
        return query == null ? super.get(PATH, token) : super.get(PATH + "?" + query, token);
    }

    // ---------- 鉴权 ----------

    @Test
    @DisplayName("不带 token → 401")
    void requiresAuth() {
        super.get(PATH, null).assertHttpStatus(401);
    }

    @Test
    @DisplayName("普通用户 → 403（用量是运营数据）")
    void normalUserIsForbidden() {
        usage(null, loginAsUser()).assertHttpStatus(403);
        super.get(PATH + "/daily", loginAsUser()).assertHttpStatus(403);
        super.get(PATH + "/models", loginAsUser()).assertHttpStatus(403);
    }

    // ---------- 空数据 ----------

    @Test
    @DisplayName("★ 一条记录都没有时：返回 0 而不是 null")
    void emptyDataReturnsZeroNotNull() {
        ApiResult result = usage(null, loginAsAdmin()).assertSuccess().assertCamelCase();

        // SUM/AVG 在空表上返回 NULL，映射到包装类型就是 null，前端拿到会显示成空白
        assertThat(result.data().path("requests").asLong()).isZero();
        assertThat(result.data().path("successCount").asLong()).isZero();
        assertThat(result.data().path("failedCount").asLong()).isZero();
        assertThat(result.data().path("promptTokens").asLong()).isZero();
        assertThat(result.data().path("completionTokens").asLong()).isZero();
        assertThat(result.data().path("totalTokens").asLong()).isZero();
        assertThat(result.data().path("avgDurationMs").asLong()).isZero();
        for (String field : new String[]{"requests", "totalTokens", "avgDurationMs"}) {
            assertThat(result.data().path(field).isNull())
                    .as("%s 不能是 null", field).isFalse();
        }
    }

    @Test
    @DisplayName("空数据时按天/按模型返回空数组，不是 null")
    void emptyListsAreArrays() {
        assertThat(usage(null, loginAsAdmin()).assertSuccess().data().isObject()).isTrue();
        assertThat(super.get(PATH + "/daily", loginAsAdmin()).assertSuccess().data().isArray()).isTrue();
        assertThat(super.get(PATH + "/models", loginAsAdmin()).assertSuccess().data().isArray()).isTrue();
    }

    // ---------- 汇总 ----------

    @Test
    @DisplayName("汇总：次数 / 成功失败 / token 合计 / 平均耗时")
    void summaryAggregates() {
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 100, 50, 0, 2L);
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 200, 100, 0, 2L);
        insertUsage("RESUME_PARSE", "qwen", "FAILED", 30, 0, 0, 2L);

        ApiResult result = usage(null, loginAsAdmin()).assertSuccess();

        assertThat(result.data().path("requests").asLong()).isEqualTo(3);
        assertThat(result.data().path("successCount").asLong()).isEqualTo(2);
        assertThat(result.data().path("failedCount").asLong()).isEqualTo(1);
        assertThat(result.data().path("promptTokens").asLong()).isEqualTo(330);
        assertThat(result.data().path("completionTokens").asLong()).isEqualTo(150);
        assertThat(result.data().path("totalTokens").asLong()).isEqualTo(480);
        assertThat(result.data().path("avgDurationMs").asLong()).isEqualTo(1000);
    }

    @Test
    @DisplayName("按天：今天的记录归到今天，且带上成功失败数")
    void dailyGroupsByDate() {
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 100, 50, 0, 2L);
        insertUsage("RAG_CHAT", "qwen", "FAILED", 10, 0, 0, 2L);
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 999, 999, 3, 2L);

        ApiResult result = super.get(PATH + "/daily", loginAsAdmin()).assertSuccess().assertCamelCase();

        assertThat(result.data()).hasSize(2);
        // 升序：3 天前那条在前
        var oldest = result.data().get(0);
        var today = result.data().get(1);
        assertThat(oldest.path("date").asText()).isEqualTo(LocalDate.now().minusDays(3).toString());
        assertThat(oldest.path("totalTokens").asLong()).isEqualTo(1998);
        assertThat(today.path("date").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(today.path("requests").asLong()).isEqualTo(2);
        assertThat(today.path("successCount").asLong()).isEqualTo(1);
        assertThat(today.path("failedCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("按模型：同一模型不同场景要分开统计")
    void byModelGroupsBySceneAndModel() {
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 100, 50, 0, 2L);
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 100, 50, 0, 2L);
        insertUsage("RESUME_PARSE", "qwen", "SUCCESS", 5000, 3000, 0, 2L);
        insertUsage("EMBEDDING", "text-embedding-v1", "SUCCESS", 20, 0, 0, 2L);

        ApiResult result = super.get(PATH + "/models", loginAsAdmin()).assertSuccess().assertCamelCase();

        assertThat(result.data()).hasSize(3);
        // 按 token 降序 —— 第一个应该是解析（8000）
        assertThat(result.data().get(0).path("scene").asText()).isEqualTo("RESUME_PARSE");
        assertThat(result.data().get(0).path("totalTokens").asLong()).isEqualTo(8000);

        var chat = result.data().get(1);
        assertThat(chat.path("scene").asText()).as("同一个模型在对话场景只统计 2 次调用").isEqualTo("RAG_CHAT");
        assertThat(chat.path("requests").asLong()).isEqualTo(2);

        var embedding = result.data().get(2);
        assertThat(embedding.path("modelName").asText()).isEqualTo("text-embedding-v1");
        assertThat(embedding.path("completionTokens").asLong()).as("embedding 没有输出 token").isZero();
    }

    // ---------- 日期参数 ----------

    @Test
    @DisplayName("★ 默认只看最近 7 天 —— 8 天前的记录不该算进来")
    void defaultsToLastSevenDays() {
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 100, 50, 1, 2L);    // 昨天，算
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 999, 999, 8, 2L);  // 8 天前，不算

        ApiResult result = usage(null, loginAsAdmin()).assertSuccess();

        assertThat(result.data().path("requests").asLong()).isEqualTo(1);
        assertThat(result.data().path("totalTokens").asLong()).isEqualTo(150);
    }

    @Test
    @DisplayName("显式指定日期范围")
    void explicitRangeIsHonoured() {
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 100, 50, 10, 2L);
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 200, 100, 3, 2L);

        String from = LocalDate.now().minusDays(5).toString();
        String to = LocalDate.now().toString();

        ApiResult result = usage("from=" + from + "&to=" + to, loginAsAdmin()).assertSuccess();

        assertThat(result.data().path("requests").asLong()).as("只该看到 3 天前那条").isEqualTo(1);
        assertThat(result.data().path("totalTokens").asLong()).isEqualTo(300);
    }

    @Test
    @DisplayName("★ 日期是左闭右开：to 当天的记录要算进来（不能漏掉最后一天）")
    void rangeIncludesTheEndDate() {
        insertUsage("RAG_CHAT", "qwen", "SUCCESS", 100, 50, 0, 2L);

        String today = LocalDate.now().toString();
        ApiResult result = usage("from=" + today + "&to=" + today, loginAsAdmin()).assertSuccess();

        assertThat(result.data().path("requests").asLong())
                .as("from=to=今天 时应该能看到今天的记录")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("开始日期晚于结束日期 → 40001")
    void reversedRangeIsRejected() {
        String from = LocalDate.now().toString();
        String to = LocalDate.now().minusDays(1).toString();

        usage("from=" + from + "&to=" + to, loginAsAdmin())
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("日期格式不对 → 400，不是 500")
    void malformedDateIsBadRequest() {
        ApiResult result = usage("from=2026/10/01", loginAsAdmin());

        assertThat(result.httpStatus())
                .as("非法日期应该是 400，实际 %s body=%s", result.httpStatus(), result.body())
                .isEqualTo(400);
    }
}
