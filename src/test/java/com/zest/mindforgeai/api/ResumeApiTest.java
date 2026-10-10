package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 简历接口：上传 / 列表 / 详情 / 归属校验 / 解析 / 优化 / 删除。
 *
 * <p>AI 调用被 mock 掉，所以这里测的是**Java 自己的职责**：
 * 参数校验、归属校验、状态流转、把 AI 结果正确落库。
 * AI 的输出质量是 Python 侧的事（那边用 fake LLM + 少量真实 E2E 覆盖）。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("简历接口")
class ResumeApiTest extends AbstractApiTest {

    private static final String SAMPLE_RESUME = """
            张伟
            男 | 28 岁 | 上海 | zhangwei@example.com
            求职意向：Java 后端开发工程师

            工作经历
            2021.07 - 至今  某某科技  Java 后端开发工程师
            - 负责订单中心开发，参与分库分表改造

            技能
            Java、Spring Boot、MySQL、Redis、Kafka
            """;

    private String token;
    private long resumeId;

    @BeforeEach
    void uploadOneResume() {
        token = loginAsUser();
        resumeId = uploadResume(token, "张伟-简历.txt", SAMPLE_RESUME).assertSuccess().longId();
    }

    private String parseStatus(long id) {
        return jdbc.queryForObject("SELECT parse_status FROM resume WHERE id = ?", String.class, id);
    }

    // ---------- 上传 ----------

    @Test
    @DisplayName("上传成功后落库，初始状态是 PENDING（还没解析）")
    void uploadCreatesPendingResume() {
        assertThat(parseStatus(resumeId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resume WHERE id = ? AND deleted = 0",
                Integer.class, resumeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("不支持的文件类型：42201")
    void unsupportedFileTypeIsRejected() {
        uploadResume(token, "木马.exe", "MZ").assertCode(ErrorCode.FILE_TYPE_UNSUPPORTED.getCode());
    }

    @Test
    @DisplayName("不带 token 上传：401")
    void uploadRequiresAuth() {
        uploadResume(null, "张伟-简历.txt", SAMPLE_RESUME).assertHttpStatus(401);
    }

    // ---------- 列表 / 详情 ----------

    @Test
    @DisplayName("列表能看到刚上传的简历")
    void listContainsUploadedResume() {
        ApiResult result = get("/app/resumes?page=1&pageSize=10", token).assertSuccess().assertCamelCase();
        assertThat(result.data().get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("详情能取到自己的简历")
    void detailOfOwnResume() {
        get("/app/resumes/" + resumeId, token).assertSuccess().assertCamelCase();
    }

    @Test
    @DisplayName("★ 别人的简历：返回 404 而不是 403（403 等于承认「这个 id 存在」）")
    void otherUsersResumeIsNotFound() {
        createAccount("t_other_user");
        String otherToken = loginRaw("/app/auth/login", "t_other_user", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        get("/app/resumes/" + resumeId, otherToken)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.RESUME_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("★ 别人不能解析我的简历")
    void otherUserCannotParseMyResume() {
        createAccount("t_other_user");
        String otherToken = loginRaw("/app/auth/login", "t_other_user", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        post("/app/resumes/" + resumeId + "/parse", otherToken, null).assertHttpStatus(404);
    }

    // ---------- 解析 ----------

    @Test
    @DisplayName("解析成功：状态转 SUCCESS，正文 / 结构化结果 / 技能都落库")
    void parsePersistsEverything() throws Exception {
        when(aiServiceClient.parseResume(any())).thenReturn(new AiServiceClient.ResumeParseData(
                SAMPLE_RESUME,
                1,
                "5 年 Java 后端，主要做订单与交易系统。",
                objectMapper.readTree("{\"name\":\"张伟\",\"skills\":[\"Java\"]}"),
                List.of(
                        new AiServiceClient.ResumeParseData.Skill("Java", "LANGUAGE", "ADVANCED", 5.0),
                        new AiServiceClient.ResumeParseData.Skill("MySQL", "TOOL", "ADVANCED", 5.0)),
                false));

        post("/app/resumes/" + resumeId + "/parse", token, null).assertSuccess();

        assertThat(parseStatus(resumeId)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT raw_content FROM resume WHERE id = ?",
                String.class, resumeId)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT summary FROM resume WHERE id = ?",
                String.class, resumeId)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT structured_json FROM resume WHERE id = ?",
                String.class, resumeId)).contains("张伟");
        // 技能是「全删全插」重写的
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resume_skill WHERE resume_id = ?",
                Integer.class, resumeId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT error_message FROM resume WHERE id = ?",
                String.class, resumeId)).isNull();
    }

    @Test
    @DisplayName("★ 解析失败：状态转 FAILED 且把原因写进 error_message（前端才能说清为什么失败）")
    void parseFailureIsRecorded() {
        when(aiServiceClient.parseResume(any()))
                .thenThrow(new BizException(ErrorCode.AI_SERVICE_ERROR, "AI 服务返回异常状态：503"));

        ApiResult result = post("/app/resumes/" + resumeId + "/parse", token, null);

        assertThat(result.isSuccess())
                .as("AI 调用失败时这次请求本身也必须是失败的，实际 body=%s", result.body())
                .isFalse();
        assertThat(parseStatus(resumeId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM resume WHERE id = ?",
                String.class, resumeId))
                .as("失败原因要落库，否则前端只能显示一个光秃秃的 FAILED")
                .isNotBlank();
    }

    @Test
    @DisplayName("★ 解析失败不清空上一次成功的结果（正文没变，旧结果依然是对的）")
    void parseFailureKeepsPreviousStructuredJson() throws Exception {
        when(aiServiceClient.parseResume(any())).thenReturn(new AiServiceClient.ResumeParseData(
                SAMPLE_RESUME, 1, "第一次成功", objectMapper.readTree("{\"name\":\"张伟\"}"),
                List.of(), false));
        post("/app/resumes/" + resumeId + "/parse", token, null).assertSuccess();

        when(aiServiceClient.parseResume(any()))
                .thenThrow(new BizException(ErrorCode.AI_SERVICE_ERROR));
        post("/app/resumes/" + resumeId + "/parse", token, null);

        assertThat(parseStatus(resumeId)).as("状态记的是「最近一次尝试」").isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT structured_json FROM resume WHERE id = ?",
                String.class, resumeId))
                .as("structuredJson 记的是「最近一次成功」，不能因为一次重试失败就删掉")
                .contains("张伟");
    }

    // ---------- AI 优化 ----------

    @Test
    @DisplayName("★ 优化：只返回建议、不落库（不能覆盖用户的原始简历）")
    void optimizeReturnsSuggestionsWithoutPersisting() throws Exception {
        // 优化要基于解析出来的正文，所以先解析一次
        when(aiServiceClient.parseResume(any())).thenReturn(new AiServiceClient.ResumeParseData(
                SAMPLE_RESUME, 1, "5 年 Java 后端", objectMapper.readTree("{}"), List.of(), false));
        post("/app/resumes/" + resumeId + "/parse", token, null).assertSuccess();
        String rawBefore = jdbc.queryForObject("SELECT raw_content FROM resume WHERE id = ?",
                String.class, resumeId);

        when(aiServiceClient.analyzeResume(any())).thenReturn(new AiServiceClient.ResumeAnalyzeData(
                72.0,
                "经历本身够用，但表述缺少量化的结果。",
                List.of("订单系统经验扎实"),
                List.of(new AiServiceClient.ResumeAnalyzeData.AnalyzeItem(
                        "WORK_EXPERIENCE",
                        "负责订单中心开发",
                        "看不出规模和结果",
                        "补上日均单量、并发量、优化前后的指标",
                        null,               // ★ needsFact 为真时 rewritten 必须是 null
                        true,
                        "日均单量和优化前后的 QPS/P99")),
                false));

        ApiResult result = post("/app/resumes/" + resumeId + "/optimize", token,
                java.util.Map.of()).assertSuccess().assertCamelCase();

        assertThat(result.data().get("items")).isNotEmpty();
        assertThat(result.data().get("items").get(0).path("needsFact").asBoolean())
                .as("needsFact 要如实透传给前端 —— 前端必须把「要补事实」和「已改写」分开展示")
                .isTrue();
        assertThat(result.data().get("items").get(0).path("rewritten").isNull())
                .as("needsFact 为真时 rewritten 必须是 null，否则用户会以为 AI 已经替他把经历写好了")
                .isTrue();

        // 优化只回建议，原始正文一个字都不能动
        assertThat(jdbc.queryForObject("SELECT raw_content FROM resume WHERE id = ?",
                String.class, resumeId))
                .as("优化是「建议」不是「改写」，原始简历不能被覆盖")
                .isEqualTo(rawBefore);
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("删除：逻辑删简历 + 物理清技能")
    void deleteRemovesResumeAndSkills() throws Exception {
        when(aiServiceClient.parseResume(any())).thenReturn(new AiServiceClient.ResumeParseData(
                SAMPLE_RESUME, 1, "摘要", objectMapper.readTree("{}"),
                List.of(new AiServiceClient.ResumeParseData.Skill("Java", "LANGUAGE", "ADVANCED", 5.0)),
                false));
        post("/app/resumes/" + resumeId + "/parse", token, null).assertSuccess();

        delete("/app/resumes/" + resumeId, token).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resume WHERE id = ? AND deleted = 0", Integer.class, resumeId))
                .as("简历表是逻辑删除").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resume_skill WHERE resume_id = ?", Integer.class, resumeId))
                .as("技能表没有 deleted 列，只能物理删 —— 不删就是孤儿数据").isZero();
    }

    @Test
    @DisplayName("取不存在的简历：404")
    void missingResumeIsNotFound() {
        get("/app/resumes/99999999", token)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.RESUME_NOT_FOUND.getCode());
    }
}
