package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 面试错题本（统计 / 列表 / 单题重练）。
 *
 * <p>这三个端点在 {@link InterviewApiTest} 里完全没覆盖 —— 那一类只测了
 * 五步状态机（创建→出题→开始→作答→结束）。错题本是「结束后」的产物，
 * 逻辑也独立（按分数 75 分划线区分薄弱 / 已掌握，且重练会重新调 AI 评分）。
 *
 * <p>★ 重点：错题本只归集<b>已结束（FINISHED）</b>面试里<b>已作答</b>的题，
 * 所以每个用例都先走完「出题→开始→答一题→结束」这条最短链路。
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("面试错题本")
class InterviewMistakeApiTest extends AbstractApiTest {

    private static final int PLANNED_QUESTIONS = 3;

    private String token;
    private long interviewId;

    @BeforeEach
    void finishOneInterview() {
        token = loginAsUser();
        interviewId = post("/app/interviews", token, Map.of(
                "title", "后端岗模拟面试",
                "interviewType", "TECHNICAL",
                "difficulty", "MEDIUM",
                "questionCount", PLANNED_QUESTIONS)).assertSuccess().longId();
        stubGenerate();
        stubAnalyzeAnswer(70.0);
        stubReport();
        post("/app/interviews/" + interviewId + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/start", token, null).assertSuccess();
        post("/app/interviews/" + interviewId + "/answers", token, Map.of(
                "questionId", String.valueOf(firstQuestionId(interviewId)),
                "answer", "堆是线程共享的，栈是线程私有的……")).assertSuccess();
        post("/app/interviews/" + interviewId + "/finish", token, null).assertSuccess();
    }

    // ------------------------------------------------------------ 统计

    @Test
    @DisplayName("★ 统计：已结束面试的作答被归集（总分 / 薄弱数 / 平均分）")
    void statsReflectFinishedInterview() {
        ApiResult res = get("/app/interviews/mistakes/stats", token).assertSuccess().assertCamelCase();

        assertThat(res.data().path("totalAnswered").asLong()).isGreaterThanOrEqualTo(1);
        // 70 分 < 75 分阈值，算薄弱
        assertThat(res.data().path("weakCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(res.data().path("masteredCount").asLong()).isZero();
        assertThat(res.data().path("averageScore").asDouble()).isGreaterThan(0);
    }

    @Test
    @DisplayName("没结束过面试：统计全 0，平均分 null")
    void statsEmptyWhenNoFinished() {
        createAccount("mistake_fresh");
        String fresh = loginRaw("/app/auth/login", "mistake_fresh", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        ApiResult res = get("/app/interviews/mistakes/stats", fresh).assertSuccess();
        assertThat(res.data().path("totalAnswered").asLong()).isZero();
        assertThat(res.data().path("weakCount").asLong()).isZero();
        assertThat(res.data().path("masteredCount").asLong()).isZero();
        assertThat(res.data().path("averageScore").isNull()).isTrue();
    }

    // ------------------------------------------------------------ 列表

    @Test
    @DisplayName("★ 薄弱列表（默认 WEAK）：含刚答且未达标的题，mastered=false，且参考答案可见")
    void listWeakContainsQuestion() {
        ApiResult res = get("/app/interviews/mistakes?masteryStatus=WEAK", token)
                .assertSuccess().assertCamelCase();

        var records = res.data().path("records");
        assertThat(records).isNotEmpty();

        boolean found = false;
        for (var r : records) {
            if (r.path("questionId").asText().equals(String.valueOf(firstQuestionId(interviewId)))) {
                found = true;
                assertThat(r.path("mastered").asBoolean()).isFalse();
                assertThat(r.path("score").asDouble()).isEqualTo(70.0);
                // 参考答案要点随题返回，与面试状态无关（错题本里必须能看到）
                assertThat(r.path("expectedPoints").size()).isGreaterThan(0);
            }
        }
        assertThat(found).as("薄弱列表里应当有刚答的那道题").isTrue();
    }

    @Test
    @DisplayName("MASTERED 列表：分数 < 75 的题不算掌握，所以为空")
    void listMasteredIsEmpty() {
        ApiResult res = get("/app/interviews/mistakes?masteryStatus=MASTERED", token).assertSuccess();
        assertThat(res.data().path("records")).isEmpty();
    }

    @Test
    @DisplayName("ALL 列表：含所有已作答的题")
    void listAllContainsQuestion() {
        ApiResult res = get("/app/interviews/mistakes?masteryStatus=ALL", token).assertSuccess();
        var records = res.data().path("records");
        assertThat(records).isNotEmpty();
        assertThat(records.findValuesAsText("questionId"))
                .contains(String.valueOf(firstQuestionId(interviewId)));
    }

    @Test
    @DisplayName("不支持的题型 / 难度过滤 → 40001")
    void listRejectsBadFilter() {
        get("/app/interviews/mistakes?questionType=NOPE", token)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        get("/app/interviews/mistakes?difficulty=NOPE", token)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 单题重练

    @Test
    @DisplayName("★ 单题重练：调 AI 重新评分，分数与掌握状态更新并落库")
    void practiceUpdatesScore() {
        long qid = firstQuestionId(interviewId);
        // 重练时覆写 stub，新得分 85（>= 75 → 掌握）
        stubAnalyzeAnswer(85.0);

        ApiResult res = post("/app/interviews/questions/" + qid + "/practice", token,
                        Map.of("answer", "现在我能讲清楚 JVM 调优了，上次那次 Full GC……"))
                .assertSuccess().assertCamelCase();

        assertThat(res.data().path("questionId").asText()).isEqualTo(String.valueOf(qid));
        assertThat(res.data().path("score").asDouble()).isEqualTo(85.0);
        assertThat(res.data().path("mastered").asBoolean()).isTrue();

        Double dbScore = jdbc.queryForObject(
                "SELECT score FROM interview_question WHERE id = ?", Double.class, qid);
        assertThat(dbScore).as("重练的新分数要落库").isEqualTo(85.0);
    }

    @Test
    @DisplayName("重练回答为空 → 40001")
    void practiceRejectsBlankAnswer() {
        long qid = firstQuestionId(interviewId);
        post("/app/interviews/questions/" + qid + "/practice", token, Map.of("answer", "  "))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 面试没结束不能重练（拿未结束面试的题）→ 409")
    void practiceRequiresFinishedInterview() {
        long other = post("/app/interviews", token, Map.of(
                "title", "未结束的面试", "questionCount", 1)).assertSuccess().longId();
        stubGenerate();
        post("/app/interviews/" + other + "/generate", token, null).assertSuccess();
        post("/app/interviews/" + other + "/start", token, null).assertSuccess();
        long qOther = firstQuestionId(other);
        post("/app/interviews/" + other + "/answers", token, Map.of(
                "questionId", String.valueOf(qOther), "answer", "x")).assertSuccess();

        post("/app/interviews/questions/" + qOther + "/practice", token,
                        Map.of("answer", "再答一次"))
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    @Test
    @DisplayName("★ 重练别人的题 → 404（不暴露题是否存在）")
    void practiceForeignQuestionIsNotFound() {
        Long other = createAccount("mistake_other");
        String otherToken = loginRaw("/app/auth/login", "mistake_other", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        long theirInterview = post("/app/interviews", otherToken, Map.of(
                "title", "别人的面试", "questionCount", 1)).assertSuccess().longId();
        stubGenerate();
        stubAnalyzeAnswer(70.0);
        stubReport();
        post("/app/interviews/" + theirInterview + "/generate", otherToken, null).assertSuccess();
        post("/app/interviews/" + theirInterview + "/start", otherToken, null).assertSuccess();
        long theirQ = firstQuestionId(theirInterview);
        post("/app/interviews/" + theirInterview + "/answers", otherToken, Map.of(
                "questionId", String.valueOf(theirQ), "answer", "x")).assertSuccess();
        post("/app/interviews/" + theirInterview + "/finish", otherToken, null).assertSuccess();

        post("/app/interviews/questions/" + theirQ + "/practice", token,
                        Map.of("answer", "套答案"))
                .assertHttpStatus(404);
    }

    // ------------------------------------------------------------ 辅助

    private long firstQuestionId(long id) {
        return jdbc.queryForObject(
                "SELECT id FROM interview_question WHERE interview_id = ? ORDER BY question_no LIMIT 1",
                Long.class, id);
    }

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

    private void stubAnalyzeAnswer(double score) {
        when(aiServiceClient.analyzeAnswer(any())).thenReturn(
                new AiServiceClient.InterviewAnalyzeData(
                        score,
                        new AiServiceClient.InterviewAnalyzeData.Technical(
                                score - 5.0, List.of("概念清楚"), List.of("缺少真实案例")),
                        new AiServiceClient.InterviewAnalyzeData.Communication(
                                score + 5.0, List.of("有点绕")),
                        List.of("GC 调优"),
                        "方向对，但没有落到自己做过的具体事情上。",
                        "你那次 Full GC 的堆参数是怎么定的？"));
    }

    private void stubReport() {
        when(aiServiceClient.buildReport(any())).thenReturn(
                new AiServiceClient.InterviewReportData(
                        9.9, 7.7, 18.0, 8.0,
                        // 这两个维度是可空的，错题本用例不关心，给 null 即可
                        null, null,
                        List.of("表达有条理"),
                        List.of("缓存一致性没答上来"),
                        List.of("复习缓存与数据库的一致性方案"),
                        "## 总体评价\n综合表现偏弱，技术深度不够。"));
    }
}
