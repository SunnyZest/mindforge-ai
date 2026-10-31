package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AI 面试：五步状态机 创建 → 出题 → 开始 → 逐题作答 → 结束。
 *
 * <p>重点在**状态流转和归属校验**，不在 AI 输出质量。
 * AI 被 mock 掉，所以每个状态都是确定可控的 —— 这正是把 AI 挡在外面换来的好处。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("AI 面试")
class InterviewApiTest extends AbstractApiTest {

    private static final int PLANNED_QUESTIONS = 3;

    private String token;
    private long interviewId;

    @BeforeEach
    void createInterview() {
        token = loginAsUser();
        interviewId = post("/app/interviews", token, Map.of(
                "title", "后端岗模拟面试",
                "interviewType", "TECHNICAL",
                "difficulty", "MEDIUM",
                "questionCount", PLANNED_QUESTIONS)).assertSuccess().longId();
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM interview WHERE id = ?", String.class, id);
    }

    private int questionRows(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM interview_question WHERE interview_id = ?",
                Integer.class, id);
    }

    private long firstQuestionId(long id) {
        return jdbc.queryForObject(
                "SELECT id FROM interview_question WHERE interview_id = ? ORDER BY question_no LIMIT 1",
                Long.class, id);
    }

    /** 出题：mock 掉 AI，返回 3 道题。 */
    private void stubGenerate() {
        when(aiServiceClient.generateQuestions(any())).thenReturn(
                new AiServiceClient.InterviewGenerateData(List.of(
                        new AiServiceClient.InterviewGenerateData.Question(
                                1, "TECHNICAL", "讲讲 JVM 的内存模型和你做过的调优。",
                                List.of("堆与栈", "GC 算法", "一次真实案例"), "HARD"),
                        new AiServiceClient.InterviewGenerateData.Question(
                                2, "TECHNICAL", "缓存与数据库的一致性怎么保证？",
                                List.of("双写一致", "延迟双删", "最终一致"), "MEDIUM"),
                        new AiServiceClient.InterviewGenerateData.Question(
                                3, "TECHNICAL", "线上 CPU 飙高你怎么排查？",
                                List.of("top", "jstack", "定位到具体线程"), "MEDIUM"))));
    }

    private void stubAnalyzeAnswer() {
        when(aiServiceClient.analyzeAnswer(any())).thenReturn(
                new AiServiceClient.InterviewAnalyzeData(
                        70.0,
                        new AiServiceClient.InterviewAnalyzeData.Technical(
                                65.0, List.of("概念清楚"), List.of("缺少真实案例")),
                        new AiServiceClient.InterviewAnalyzeData.Communication(
                                80.0, List.of("有点绕")),
                        List.of("GC 调优"),
                        "方向对，但没有落到自己做过的具体事情上。",
                        "你那次 Full GC 的堆参数是怎么定的？"));
    }

    private void stubReport() {
        when(aiServiceClient.buildReport(any())).thenReturn(
                new AiServiceClient.InterviewReportData(
                        9.9, 7.7, 18.0, 8.0,
                        6.5, 8.8,
                        List.of("表达有条理"),
                        List.of("缓存一致性没答上来"),
                        List.of("复习缓存与数据库的一致性方案"),
                        "## 总体评价\n综合表现偏弱，技术深度不够。"));
    }

    /**
     * 出题 → 开始 → 答第一题。
     *
     * <p>结束面试要求至少答过一题（一道题都没答就出报告没有意义，
     * 服务端会返回 40902），所以凡是走到 finish 的用例都得先经过这里。
     */
    private void prepareAnsweredInterview() {
        stubGenerate();
        stubAnalyzeAnswer();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/answers", token, Map.of(
                "questionId", String.valueOf(firstQuestionId(interviewId)),
                "answer", "堆是线程共享的，栈是线程私有的，GC 主要看堆的分代回收……")).assertSuccess();
    }

    // ---------- 创建 ----------

    @Test
    @DisplayName("创建后状态是 CREATED，题目行数是 0（创建和出题是两步）")
    void createStartsInCreatedState() {
        assertThat(status(interviewId)).isEqualTo("CREATED");
        assertThat(questionRows(interviewId)).as("创建时还不该有题").isZero();
    }

    @Test
    @DisplayName("★ 题目数量超上限（21）→ 40001")
    void questionCountOverLimitIsRejected() {
        post("/app/interviews", token, Map.of("title", "x", "questionCount", 21))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("创建时指定别人的简历 → 404（归属校验要早失败）")
    void createWithOthersResumeIsRejected() {
        createAccount("t_other_user");
        String otherToken = loginRaw("/app/auth/login", "t_other_user", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        long othersResume = uploadResume(otherToken, "别人的简历.txt", "张伟\nJava").assertSuccess().longId();

        post("/app/interviews", token, Map.of("title", "x", "resumeId", String.valueOf(othersResume)))
                .assertHttpStatus(404);
    }

    // ---------- 出题 ----------

    @Test
    @DisplayName("出题：题目落库，题号 1..N")
    void generatePersistsQuestions() {
        stubGenerate();

        ApiResult result = post("/app/interviews/" + interviewId + "/generate", token, null)
                .assertSuccess().assertCamelCase();

        assertThat(questionRows(interviewId)).isEqualTo(PLANNED_QUESTIONS);
        assertThat(status(interviewId)).as("出完题还不算开始").isEqualTo("CREATED");
        assertThat(result.data().get("questions")).hasSize(PLANNED_QUESTIONS);
    }

    @Test
    @DisplayName("★ 面试进行中不返回 expectedPoints（给了等于提前把答案交给用户）")
    void expectedPointsHiddenWhileInProgress() {
        stubGenerate();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();

        ApiResult detail = get("/app/interviews/" + interviewId, token).assertSuccess();
        var first = detail.data().get("questions").get(0);
        assertThat(first.path("expectedPoints").isMissingNode() || first.path("expectedPoints").isNull())
                .as("进行中不能返回参考答案要点，实际：%s", first)
                .isTrue();
    }

    @Test
    @DisplayName("★ 已开始的面试不能重新出题（用户答过的题会对不上）")
    void cannotRegenerateAfterStart() {
        stubGenerate();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();

        post("/app/interviews/" + interviewId + "/generate", token, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    // ---------- 开始 ----------

    @Test
    @DisplayName("★ 没出题就点开始 → 409（不能进到一个「0 道题」的面试里）")
    void cannotStartWithoutQuestions() {
        post("/app/interviews/" + interviewId + "/start", token, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
        assertThat(status(interviewId)).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("出题后开始 → IN_PROGRESS")
    void startMovesToInProgress() {
        stubGenerate();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();

        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();
        assertThat(status(interviewId)).isEqualTo("IN_PROGRESS");
    }

    // ---------- 作答 ----------

    @Test
    @DisplayName("作答：评分和分析落库")
    void answerPersistsScoreAndAnalysis() {
        stubGenerate();
        stubAnalyzeAnswer();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();

        post("/app/interviews/" + interviewId + "/answers", token, Map.of(
                "questionId", String.valueOf(firstQuestionId(interviewId)),
                "answer", "堆是线程共享的，栈是线程私有的……")).assertSuccess().assertCamelCase();

        assertThat(jdbc.queryForObject(
                "SELECT score FROM interview_question WHERE interview_id = ? AND answered_at IS NOT NULL",
                java.math.BigDecimal.class, interviewId))
                .as("分数要落库").isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT ai_analysis FROM interview_question WHERE interview_id = ? AND answered_at IS NOT NULL",
                String.class, interviewId))
                .as("逐题分析要落库（生成报告时还要拿它喂给模型）").isNotBlank();
    }

    @Test
    @DisplayName("★ 空回答：给 0 分但不报错（跳过一题是正常行为）")
    void blankAnswerIsAcceptedWithZeroScore() {
        stubGenerate();
        stubAnalyzeAnswer();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();

        post("/app/interviews/" + interviewId + "/answers", token, Map.of(
                "questionId", String.valueOf(firstQuestionId(interviewId)),
                "answer", "")).assertSuccess();
    }

    @Test
    @DisplayName("★ 用别的面试的题目 id 作答 → 40411（不能串场）")
    void answeringWithForeignQuestionIdIsRejected() {
        stubGenerate();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();

        long otherInterview = post("/app/interviews", token, Map.of(
                "title", "另一场", "questionCount", 1)).assertSuccess().longId();
        stubGenerate();
        post("/app/interviews/" + otherInterview + "/generate", token, null).assertSuccess();

        post("/app/interviews/" + interviewId + "/answers", token, Map.of(
                "questionId", String.valueOf(firstQuestionId(otherInterview)),
                "answer", "随便答点")).assertCode(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("没开始就作答 → 409")
    void cannotAnswerBeforeStart() {
        stubGenerate();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();

        post("/app/interviews/" + interviewId + "/answers", token, Map.of(
                "questionId", String.valueOf(firstQuestionId(interviewId)),
                "answer", "x")).assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    // ---------- 结束与报告 ----------

    @Test
    @DisplayName("★ 还没结束就取报告 → 40412")
    void reportIsNotAvailableBeforeFinish() {
        get("/app/interviews/" + interviewId + "/report", token)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.INTERVIEW_REPORT_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("结束：状态转 FINISHED，报告四个分数 + 三个列表 + 正文都落库")
    void finishGeneratesReport() {
        prepareAnsweredInterview();
        stubReport();

        ApiResult report = post("/app/interviews/" + interviewId + "/finish", token, null)
                .assertSuccess().assertCamelCase();

        assertThat(status(interviewId)).isEqualTo("FINISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_report WHERE interview_id = ?",
                Integer.class, interviewId)).isEqualTo(1);
        assertThat(report.data().path("overallScore").asDouble()).isEqualTo(9.9);
        assertThat(report.data().path("reportText").asText()).isNotBlank();

        // 2026-10-30 新增的两个维度：必须一路落到库里再原样返回，
        // 不能只有 VO 有值而库里是 NULL（那样历史查询会前后不一致）
        assertThat(report.data().path("engineeringScore").asDouble()).isEqualTo(6.5);
        assertThat(report.data().path("jobFitScore").asDouble()).isEqualTo(8.8);
        assertThat(jdbc.queryForObject(
                "SELECT engineering_score FROM interview_report WHERE interview_id = ?",
                BigDecimal.class, interviewId)).isEqualByComparingTo("6.5");
        assertThat(jdbc.queryForObject(
                "SELECT job_fit_score FROM interview_report WHERE interview_id = ?",
                BigDecimal.class, interviewId)).isEqualByComparingTo("8.8");
    }

    @Test
    @DisplayName("历史报告（两列为 NULL）照常返回：这两个字段为 null，总分仍是原值")
    void historicalReportStillWorks() {
        // 模拟 2026-10-30 之前生成的报告：行存在，但没有这两个维度。
        // 这条守的是「前端拿到 null 不会崩」和「老总分不被重算」两件事。
        prepareAnsweredInterview();
        stubReport();
        post("/app/interviews/" + interviewId + "/finish", token, null).assertSuccess();

        jdbc.update("UPDATE interview_report SET engineering_score = NULL, job_fit_score = NULL "
                + "WHERE interview_id = ?", interviewId);

        ApiResult report = get("/app/interviews/" + interviewId + "/report", token)
                .assertSuccess().assertCamelCase();

        // 注意：项目没配 NON_NULL，null 字段照常出现在 JSON 里（值是 null 而不是字段缺失）。
        // 所以这里用 hasNonNull 判断「没有非 null 值」—— 比 isNull() 更准确，
        // 它同时挡住「字段缺失」和「字段为 null」两种情况。
        assertThat(report.data().hasNonNull("engineeringScore"))
                .as("历史报告不该凭空冒出一个工程规范分").isFalse();
        assertThat(report.data().hasNonNull("jobFitScore")).isFalse();
        // 老总分保持原值，不因为缺维度被重算
        assertThat(report.data().path("overallScore").asDouble()).isEqualTo(9.9);
    }

    @Test
    @DisplayName("★ 结束后才返回 expectedPoints（此时用户已经答完了）")
    void expectedPointsVisibleAfterFinish() {
        prepareAnsweredInterview();
        stubReport();
        post("/app/interviews/" + interviewId + "/finish", token, null).assertSuccess();

        ApiResult detail = get("/app/interviews/" + interviewId, token).assertSuccess();
        var first = detail.data().get("questions").get(0);
        assertThat(first.path("expectedPoints").isArray())
                .as("结束后应该能看到参考答案要点，实际：%s", first)
                .isTrue();
    }

    @Test
    @DisplayName("已结束的面试不能再开始")
    void cannotRestartFinishedInterview() {
        prepareAnsweredInterview();
        stubReport();
        post("/app/interviews/" + interviewId + "/finish", token, null).assertSuccess();

        post("/app/interviews/" + interviewId + "/start", token, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    // ---------- 删除与归属 ----------

    @Test
    @DisplayName("删除：连带物理清掉题目和报告")
    void deleteCascadesQuestionsAndReport() {
        prepareAnsweredInterview();
        stubReport();
        post("/app/interviews/" + interviewId + "/finish", token, null).assertSuccess();

        delete("/app/interviews/" + interviewId, token).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview WHERE id = ? AND deleted = 0",
                Integer.class, interviewId)).isZero();
        assertThat(questionRows(interviewId))
                .as("题目表没有 deleted 列，必须物理删").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_report WHERE interview_id = ?",
                Integer.class, interviewId))
                .as("报告表也要一起清，否则留孤儿").isZero();
    }

    @Test
    @DisplayName("别人的面试：404")
    void otherUsersInterviewIsNotFound() {
        createAccount("t_other_user");
        String otherToken = loginRaw("/app/auth/login", "t_other_user", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        get("/app/interviews/" + interviewId, otherToken).assertHttpStatus(404);
        post("/app/interviews/" + interviewId + "/start", otherToken, null).assertHttpStatus(404);
        delete("/app/interviews/" + interviewId, otherToken).assertHttpStatus(404);
    }
}
