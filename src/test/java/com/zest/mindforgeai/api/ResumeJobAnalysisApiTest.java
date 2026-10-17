package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户端简历-JD 匹配（POST 触发分析 / GET 取上次结果）。
 *
 * <p>AI 调用被 mock 掉，这里测的是 Java 自己的职责：
 * 参数与归属校验、把 AI 的五类结论正确落库、同一对（简历 + 职位）只留一条、
 * 以及「没匹配过返回 40413」这个前端用来决定显示「开始分析」按钮的约定。
 *
 * <p>★ 重点：匹配的输入是 {@code resume.raw_content} / {@code job.raw_content} 两段原文，
 * 所以本测试在 setUp 里把这两段正文直接置上（不上真正的解析流程，
 * 解析本身由 ResumeApiTest / JobApiTest 覆盖）。
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("简历-JD 匹配")
class ResumeJobAnalysisApiTest extends AbstractApiTest {

    private static final String PATH = "/app/resume-job-analysis";

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

    private static final String SAMPLE_JD = """
            高级 Java 后端开发工程师

            任职要求
            1. 5 年以上 Java 后端开发经验
            2. 熟悉 MySQL、Redis、Kafka
            """;

    private String token;
    private Long myUserId;
    private long resumeId;
    private long jobId;

    @BeforeEach
    void setUp() {
        token = loginAsUser();
        myUserId = userId(USER_NAME);

        // 简历：上传后还是 PENDING（raw_content 为空），这里直接把正文置上
        resumeId = uploadResume(token, "张伟-简历.txt", SAMPLE_RESUME).assertSuccess().longId();
        jdbc.update("UPDATE resume SET raw_content = ? WHERE id = ?", SAMPLE_RESUME, resumeId);

        // 职位：创建时 jobDescription 已是 raw_content，再显式置一次以防万一
        jobId = post("/app/jobs", token, Map.of(
                "title", "高级 Java 后端开发工程师",
                "companyName", "某某互联网公司",
                "location", "上海",
                "jobDescription", SAMPLE_JD)).assertSuccess().longId();
        jdbc.update("UPDATE job SET raw_content = ? WHERE id = ?", SAMPLE_JD, jobId);
    }

    // ------------------------------------------------------------ POST 触发分析

    @Test
    @DisplayName("★ 触发匹配：AI 的五类结论原样回显，不丢字段")
    void analyzeEchoesAiResult() {
        List<AiServiceClient.ResumeMatchData.MatchItem> items = List.of(
                new AiServiceClient.ResumeMatchData.MatchItem(
                        "SKILL", "熟悉 Java", "MATCHED", "简历写明 5 年 Java 经验", "保持"),
                new AiServiceClient.ResumeMatchData.MatchItem(
                        "SKILL", "熟悉 Kafka", "REAL_GAP", null, "去补 Kafka 实战"));
        stubMatch(72.0, items);

        ApiResult res = post(PATH, token, Map.of(
                        "resumeId", String.valueOf(resumeId),
                        "jobId", String.valueOf(jobId)))
                .assertSuccess().assertCamelCase();

        assertThat(res.data().path("overallScore").asDouble()).isEqualTo(72.0);
        assertThat(res.str("summary")).contains("匹配度中等");
        assertThat(res.str("resumeId")).isEqualTo(String.valueOf(resumeId));
        assertThat(res.str("jobId")).isEqualTo(String.valueOf(jobId));

        // ★ 核心字段：五类结论逐条透传
        var its = res.data().path("items");
        assertThat(its).hasSize(2);
        assertThat(its.get(0).path("category").asText()).isEqualTo("SKILL");
        assertThat(its.get(0).path("verdict").asText()).isEqualTo("MATCHED");
        assertThat(its.get(0).path("evidence").asText()).isEqualTo("简历写明 5 年 Java 经验");
        assertThat(its.get(1).path("verdict").asText()).isEqualTo("REAL_GAP");
        assertThat(its.get(1).path("evidence").isNull()).isTrue();
        assertThat(its.get(1).path("advice").asText()).isEqualTo("去补 Kafka 实战");

        // 摘要字段也回显
        assertThat(res.data().path("matchedSkills")).hasSize(2);
        assertThat(res.data().path("matchedSkills").get(0).asText()).isEqualTo("Java");
        assertThat(res.data().path("missingSkills").get(0).asText()).isEqualTo("Kafka");
        assertThat(res.data().path("suggestions").get(0).asText()).isEqualTo("补一个高并发项目经历");
    }

    @Test
    @DisplayName("★ 落库，且同一对（简历 + 职位）只留一条：重跑覆盖")
    void analyzePersistsAndOverwrites() {
        stubMatch(60.0, List.of());
        post(PATH, token, Map.of(
                "resumeId", String.valueOf(resumeId),
                "jobId", String.valueOf(jobId))).assertSuccess();

        assertThat(countRows()).isEqualTo(1);

        // 重跑，分数变了 —— 必须覆盖而不是追加
        stubMatch(88.0, List.of());
        post(PATH, token, Map.of(
                "resumeId", String.valueOf(resumeId),
                "jobId", String.valueOf(jobId))).assertSuccess();

        assertThat(countRows()).as("先删后插，同一对只保留一条").isEqualTo(1);
        Double score = jdbc.queryForObject(
                "SELECT overall_score FROM resume_job_analysis"
                        + " WHERE user_id = ? AND resume_id = ? AND job_id = ?",
                Double.class, myUserId, resumeId, jobId);
        assertThat(score).as("覆盖后是最新的分数").isEqualTo(88.0);
    }

    @Test
    @DisplayName("★ 简历没解析过（rawContent 空）→ 40001，且【不调 AI】")
    void analyzeRejectsUnparsedResume() {
        jdbc.update("UPDATE resume SET raw_content = '' WHERE id = ?", resumeId);

        post(PATH, token, Map.of(
                        "resumeId", String.valueOf(resumeId),
                        "jobId", String.valueOf(jobId)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        verify(aiServiceClient, never()).matchResume(any());
    }

    @Test
    @DisplayName("★ 职位没解析过（rawContent 空）→ 40001，且【不调 AI】")
    void analyzeRejectsUnparsedJob() {
        jdbc.update("UPDATE job SET raw_content = '' WHERE id = ?", jobId);

        post(PATH, token, Map.of(
                        "resumeId", String.valueOf(resumeId),
                        "jobId", String.valueOf(jobId)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        verify(aiServiceClient, never()).matchResume(any());
    }

    @Test
    @DisplayName("★ 拿别人的简历来匹配 → 40409（不暴露 id 是否存在），不调 AI")
    void analyzeOtherUsersResumeIsNotFound() {
        Long other = createAccount("rja_other_r");
        String otherToken = loginRaw("/app/auth/login", "rja_other_r", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        long othersResume = uploadResume(otherToken, "别人.txt", SAMPLE_RESUME).assertSuccess().longId();
        jdbc.update("UPDATE resume SET raw_content = ? WHERE id = ?", SAMPLE_RESUME, othersResume);

        post(PATH, token, Map.of(
                        "resumeId", String.valueOf(othersResume),
                        "jobId", String.valueOf(jobId)))
                .assertHttpStatus(404)
                .assertCode(ErrorCode.RESUME_NOT_FOUND.getCode());

        verify(aiServiceClient, never()).matchResume(any());
    }

    @Test
    @DisplayName("★ 拿别人的职位来匹配 → 40408，不调 AI")
    void analyzeOtherUsersJobIsNotFound() {
        Long other = createAccount("rja_other_j");
        String otherToken = loginRaw("/app/auth/login", "rja_other_j", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        long othersJob = post("/app/jobs", otherToken, Map.of(
                "title", "别人的职位", "companyName", "x", "jobDescription", SAMPLE_JD))
                .assertSuccess().longId();
        jdbc.update("UPDATE job SET raw_content = ? WHERE id = ?", SAMPLE_JD, othersJob);

        post(PATH, token, Map.of(
                        "resumeId", String.valueOf(resumeId),
                        "jobId", String.valueOf(othersJob)))
                .assertHttpStatus(404)
                .assertCode(ErrorCode.JOB_NOT_FOUND.getCode());

        verify(aiServiceClient, never()).matchResume(any());
    }

    @Test
    @DisplayName("未登录 → 401")
    void analyzeRequiresAuth() {
        post(PATH, null, Map.of(
                "resumeId", String.valueOf(resumeId),
                "jobId", String.valueOf(jobId))).assertHttpStatus(401);
    }

    @Test
    @DisplayName("resumeId 留空 / 不是数字 → 40001")
    void analyzeValidatesInput() {
        post(PATH, token, Map.of("resumeId", "  ", "jobId", String.valueOf(jobId)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(PATH, token, Map.of("resumeId", "abc", "jobId", String.valueOf(jobId)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("★ userId / resumeId / jobId / 两段原文都透传给 AI 服务（串了会记到别人头上）")
    void requestIsPassedThroughToAi() {
        stubMatch(70.0, List.of());
        post(PATH, token, Map.of(
                "resumeId", String.valueOf(resumeId),
                "jobId", String.valueOf(jobId))).assertSuccess();

        ArgumentCaptor<AiServiceClient.ResumeMatchRequest> captor =
                ArgumentCaptor.forClass(AiServiceClient.ResumeMatchRequest.class);
        verify(aiServiceClient).matchResume(captor.capture());
        AiServiceClient.ResumeMatchRequest sent = captor.getValue();

        assertThat(sent.userId()).isEqualTo(String.valueOf(myUserId));
        assertThat(sent.resumeId()).isEqualTo(String.valueOf(resumeId));
        assertThat(sent.jobId()).isEqualTo(String.valueOf(jobId));
        assertThat(sent.resumeText()).isEqualTo(SAMPLE_RESUME);
        assertThat(sent.jobText()).isEqualTo(SAMPLE_JD);
    }

    // ------------------------------------------------------------ GET 取上次

    @Test
    @DisplayName("★ 没匹配过 → 40413（前端据此显示「开始分析」按钮）")
    void getLatestNoneReturns40413() {
        get(PATH + "?resumeId=" + resumeId + "&jobId=" + jobId, token)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.RESUME_JOB_ANALYSIS_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("取上次结果：返回刚存的匹配，五类结论不丢")
    void getLatestReturnsStoredResult() {
        List<AiServiceClient.ResumeMatchData.MatchItem> items = List.of(
                new AiServiceClient.ResumeMatchData.MatchItem(
                        "SKILL", "熟悉 Java", "MATCHED", "简历写明 5 年 Java", "保持"));
        stubMatch(65.0, items);
        post(PATH, token, Map.of(
                "resumeId", String.valueOf(resumeId),
                "jobId", String.valueOf(jobId))).assertSuccess();

        ApiResult res = get(PATH + "?resumeId=" + resumeId + "&jobId=" + jobId, token)
                .assertSuccess().assertCamelCase();

        assertThat(res.data().path("overallScore").asDouble()).isEqualTo(65.0);
        assertThat(res.data().path("items")).hasSize(1);
        assertThat(res.data().path("items").get(0).path("verdict").asText()).isEqualTo("MATCHED");
        assertThat(res.str("analysisId")).isNotBlank();
    }

    @Test
    @DisplayName("另一对（简历 + 职位）没匹配过，也不返回别人的结果")
    void getLatestIsScopedToPair() {
        stubMatch(65.0, List.of());
        long otherJob = post("/app/jobs", token, Map.of(
                "title", "对照职位", "companyName", "x", "jobDescription", SAMPLE_JD))
                .assertSuccess().longId();
        jdbc.update("UPDATE job SET raw_content = ? WHERE id = ?", SAMPLE_JD, otherJob);
        post(PATH, token, Map.of(
                "resumeId", String.valueOf(resumeId),
                "jobId", String.valueOf(jobId))).assertSuccess();

        // 换一对没匹配过的（resumeId, otherJob）
        get(PATH + "?resumeId=" + resumeId + "&jobId=" + otherJob, token)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.RESUME_JOB_ANALYSIS_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("取上次结果也受归属约束：别人的匹配结果看不到")
    void getLatestOfOthersIsNotFound() {
        stubMatch(65.0, List.of());
        long otherResume = uploadResume(token, "对照简历.txt", SAMPLE_RESUME).assertSuccess().longId();
        jdbc.update("UPDATE resume SET raw_content = ? WHERE id = ?", SAMPLE_RESUME, otherResume);
        post(PATH, token, Map.of(
                "resumeId", String.valueOf(otherResume),
                "jobId", String.valueOf(jobId))).assertSuccess();

        // 用另一个账号去查这对结果 —— 归属是按当前用户筛的，查不到
        createAccount("rja_viewer");
        String viewerToken = loginRaw("/app/auth/login", "rja_viewer", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        get(PATH + "?resumeId=" + otherResume + "&jobId=" + jobId, viewerToken)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.RESUME_JOB_ANALYSIS_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 辅助

    private Integer countRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM resume_job_analysis"
                        + " WHERE user_id = ? AND resume_id = ? AND job_id = ?",
                Integer.class, myUserId, resumeId, jobId);
    }

    private void stubMatch(double score, List<AiServiceClient.ResumeMatchData.MatchItem> items) {
        when(aiServiceClient.matchResume(any())).thenReturn(new AiServiceClient.ResumeMatchData(
                score,
                "整体：候选人与岗位匹配度中等，技能基本覆盖，缺分布式经验。",
                items,
                List.of("Java", "Spring Boot"),
                List.of("Kafka"),
                List.of("5 年 Java 后端"),
                List.of("分布式架构"),
                List.of("补一个高并发项目经历"),
                false));
    }
}
