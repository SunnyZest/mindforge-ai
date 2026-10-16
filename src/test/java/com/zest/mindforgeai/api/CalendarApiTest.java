package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面试日历（求职日程）。
 *
 * <p>重点两块：
 * <ul>
 *   <li>常规约定 —— 归属校验走 404、字段校验、时间范围过滤；</li>
 *   <li><b>与 job.next_action_at 的一致性</b> —— 日程是事实来源，职位的「待办提醒」
 *       要跟着日程变；但<b>没有未来日程时不能把用户手填的提醒清掉</b>。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("面试日历")
class CalendarApiTest extends AbstractApiTest {

    private static final String PATH = "/app/calendar/events";

    /** 三场日程的偏移天数：+1 / +3 / +10，用来验证「最近一场」的选取 */
    private static final int D1 = 1;
    private static final int D2 = 3;
    private static final int D3 = 10;

    private String token;
    private Long jobId;

    @BeforeEach
    void setUp() {
        token = loginAsUser();
        jobId = createJob(token, "高级 Java 后端开发工程师", "某某互联网公司");
    }

    // ------------------------------------------------------------ 基础 CRUD

    @Test
    @DisplayName("新建日程：字段回显，endAt 由开始时间 + 时长算出")
    void createReturnsFields() {
        LocalDateTime at = tomorrowAt(14, 0);

        ApiResult res = post(PATH, token, eventBody(jobId, "INTERVIEW_1", "某某公司 技术一面",
                at, 60, "ONLINE", "腾讯会议 123-456", "面试官：王老师")).assertSuccess();

        assertThat(res.data().path("eventType").asText()).isEqualTo("INTERVIEW_1");
        assertThat(res.data().path("title").asText()).isEqualTo("某某公司 技术一面");
        assertThat(res.data().path("status").asText()).isEqualTo("PENDING");
        assertThat(res.data().path("eventMode").asText()).isEqualTo("ONLINE");
        assertThat(res.data().path("companyName").asText()).isEqualTo("某某互联网公司");
        assertThat(LocalDateTime.parse(res.data().path("eventAt").asText())).isEqualTo(at);
        assertThat(LocalDateTime.parse(res.data().path("endAt").asText())).isEqualTo(at.plusMinutes(60));
    }

    @Test
    @DisplayName("不填时长 → endAt 为空，不瞎编一个结束时间")
    void endAtIsNullWithoutDuration() {
        ApiResult res = post(PATH, token, eventBody(jobId, "WRITTEN_TEST", "在线笔试",
                tomorrowAt(10, 0), null, null, null, null)).assertSuccess();

        assertThat(res.data().path("endAt").isNull()).isTrue();
        assertThat(res.data().path("eventMode").isNull()).isTrue();
    }

    @Test
    @DisplayName("修改日程：整体覆盖，并保留原状态")
    void updateOverwrites() {
        long id = post(PATH, token, eventBody(jobId, "INTERVIEW_1", "一面", tomorrowAt(10, 0),
                60, null, null, null)).assertSuccess().longId();

        ApiResult res = put(PATH + "/" + id, token, eventBody(jobId, "INTERVIEW_2", "二面改期",
                tomorrowAt(15, 30), 90, "ONSITE", "张江 3 号楼", null)).assertSuccess();

        assertThat(res.data().path("eventType").asText()).isEqualTo("INTERVIEW_2");
        assertThat(res.data().path("title").asText()).isEqualTo("二面改期");
        assertThat(res.data().path("durationMinutes").asInt()).isEqualTo(90);
    }

    @Test
    @DisplayName("状态流转 PENDING → DONE → CANCELLED")
    void statusTransition() {
        long id = post(PATH, token, eventBody(jobId, "HR", "HR 面", tomorrowAt(16, 0),
                null, null, null, null)).assertSuccess().longId();

        assertThat(put(PATH + "/" + id, token, eventBody(jobId, "HR", "HR 面", tomorrowAt(16, 0),
                null, null, null, null, "DONE")).assertSuccess()
                .data().path("status").asText()).isEqualTo("DONE");

        assertThat(put(PATH + "/" + id, token, eventBody(jobId, "HR", "HR 面", tomorrowAt(16, 0),
                null, null, null, null, "CANCELLED")).assertSuccess()
                .data().path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("删除后日历里就查不到了")
    void deleteRemovesFromCalendar() {
        long id = post(PATH, token, eventBody(jobId, "OTHER", "宣讲会", tomorrowAt(19, 0),
                null, null, null, null)).assertSuccess().longId();
        assertThat(listRange(0, 5).size()).isEqualTo(1);

        delete(PATH + "/" + id, token).assertSuccess();

        assertThat(listRange(0, 5)).isEmpty();
    }

    // ------------------------------------------------------------ 时间范围

    @Test
    @DisplayName("时间范围过滤：只返回区间内的日程")
    void listFiltersByRange() {
        createEvent(D1, "INTERVIEW_1", "一面");
        createEvent(D2, "INTERVIEW_2", "二面");
        createEvent(D3, "HR", "HR 面");

        // 今天 ~ +2 天：只有 +1 那场
        assertThat(listRange(0, 2)).hasSize(1);
        // 今天 ~ +5 天：+1、+3 两场
        assertThat(listRange(0, 5)).hasSize(2);
        // 今天 ~ +15 天：三场
        assertThat(listRange(0, 15)).hasSize(3);
    }

    @Test
    @DisplayName("按时间升序返回 —— 前端直接铺到日历上，不该再排一次")
    void listIsSortedByTime() {
        createEvent(D3, "HR", "HR 面");
        createEvent(D1, "INTERVIEW_1", "一面");
        createEvent(D2, "INTERVIEW_2", "二面");

        var times = listRange(0, 15).findValuesAsText("eventAt").stream()
                .map(LocalDateTime::parse)
                .toList();

        assertThat(times).isSorted();
        assertThat(times.get(0)).isBefore(times.get(1));
    }

    @Test
    @DisplayName("不传范围 → 返回当前月")
    void defaultRangeIsCurrentMonth() {
        // 本月的日程（今天）应该出现；下个月的不会
        post(PATH, token, eventBody(jobId, "OTHER", "今天的事", LocalDate.now().atTime(9, 0),
                null, null, null, null)).assertSuccess();
        post(PATH, token, eventBody(jobId, "OTHER", "下月的事",
                LocalDate.now().plusMonths(1).withDayOfMonth(15).atTime(9, 0),
                null, null, null, null)).assertSuccess();

        var titles = get(PATH, token).assertSuccess().data().findValuesAsText("title");
        assertThat(titles).contains("今天的事").doesNotContain("下月的事");
    }

    @Test
    @DisplayName("开始日期晚于结束日期 → 40001")
    void invalidRangeIsRejected() {
        get(PATH + "?from=" + LocalDate.now().plusDays(5) + "&to=" + LocalDate.now(), token)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("查询跨度超过 366 天 → 40001（别拿接口当全量导出）")
    void tooWideRangeIsRejected() {
        get(PATH + "?from=" + LocalDate.now() + "&to=" + LocalDate.now().plusDays(400), token)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 字段校验

    @Test
    @DisplayName("类型 / 标题 / 时间 不合法 → 40001")
    void validationRejectsBadInput() {
        Map<String, Object> bad = eventBody(jobId, "NOT_A_TYPE", "x", tomorrowAt(10, 0),
                null, null, null, null);
        post(PATH, token, bad).assertCode(ErrorCode.PARAM_INVALID.getCode());

        post(PATH, token, eventBody(jobId, "OTHER", "   ", tomorrowAt(10, 0),
                null, null, null, null)).assertCode(ErrorCode.PARAM_INVALID.getCode());

        Map<String, Object> noTime = eventBody(jobId, "OTHER", "x", null, null, null, null, null);
        post(PATH, token, noTime).assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("不选职位 / 职位 id 非法 → 40001")
    void jobIdIsRequired() {
        // ① 完全不传 jobId → @NotBlank 拦下
        Map<String, Object> noJob = eventBodyRaw(null, "OTHER", "x", tomorrowAt(10, 0));
        post(PATH, token, noJob).assertCode(ErrorCode.PARAM_INVALID.getCode());

        // ② 传了但不是数字 → 走到解析那步才失败，同样归到 40001
        post(PATH, token, eventBodyRaw("not-a-number", "OTHER", "x", tomorrowAt(10, 0)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("时长超过 24 小时 → 40001")
    void durationIsCapped() {
        post(PATH, token, eventBody(jobId, "OTHER", "x", tomorrowAt(10, 0),
                1441, null, null, null)).assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 归属校验

    @Test
    @DisplayName("挂到别人的职位上 → 40408（不是 403，避免泄露 id 是否存在）")
    void cannotAttachToOthersJob() {
        String otherToken = loginAs("cal_other");

        post(PATH, otherToken, eventBody(jobId, "OTHER", "偷挂", tomorrowAt(10, 0),
                null, null, null, null)).assertCode(ErrorCode.JOB_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("改 / 删别人的日程 → 40417")
    void cannotTouchOthersEvent() {
        long id = createEvent(D1, "INTERVIEW_1", "一面");

        String otherToken = loginAs("cal_other2");

        put(PATH + "/" + id, otherToken, eventBody(jobId, "OTHER", "篡改", tomorrowAt(10, 0),
                null, null, null, null)).assertCode(ErrorCode.JOB_EVENT_NOT_FOUND.getCode());
        delete(PATH + "/" + id, otherToken).assertCode(ErrorCode.JOB_EVENT_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("别人的日程不会出现在我的日历里")
    void calendarIsScopedToUser() {
        createEvent(D1, "INTERVIEW_1", "我的一面");

        String otherToken = loginAs("cal_other3");

        assertThat(get(PATH, otherToken).assertSuccess().data()).isEmpty();
    }

    // ------------------------------------------------- 与 job.next_action_at 的一致性

    @Test
    @DisplayName("★ 新建日程 → 职位的待办提醒回写为最近一场")
    void nextActionFollowsEarliestEvent() {
        createEvent(D3, "HR", "HR 面");
        assertThat(nextActionAt()).isEqualTo(tomorrowAt(D3, 10, 0));

        createEvent(D1, "INTERVIEW_1", "一面");
        assertThat(nextActionAt()).isEqualTo(tomorrowAt(D1, 10, 0));
    }

    @Test
    @DisplayName("★ 最近一场标记完成 → 待办提醒顺延到下一场")
    void nextActionMovesOnWhenDone() {
        long first = createEvent(D1, "INTERVIEW_1", "一面");
        createEvent(D3, "INTERVIEW_2", "二面");
        assertThat(nextActionAt()).isEqualTo(tomorrowAt(D1, 10, 0));

        put(PATH + "/" + first, token, eventBody(jobId, "INTERVIEW_1", "一面", tomorrowAt(D1, 10, 0),
                null, null, null, null, "DONE")).assertSuccess();

        assertThat(nextActionAt()).isEqualTo(tomorrowAt(D3, 10, 0));
    }

    @Test
    @DisplayName("★ 删掉最后一场未来日程 → 指向它的提醒被清掉（不留僵尸提醒）")
    void nextActionIsClearedWithLastEvent() {
        long id = createEvent(D1, "INTERVIEW_1", "一面");
        assertThat(nextActionAt()).isEqualTo(tomorrowAt(D1, 10, 0));

        delete(PATH + "/" + id, token).assertSuccess();

        // 提醒本来正指向这场，删了就该跟着清 —— 否则卡片上会挂着一条「已经不存在」的提醒
        assertThat(nextActionAt()).isNull();
    }

    @Test
    @DisplayName("★ 提醒不是指向被删那场时不动它（别误删用户手填的）")
    void manualNextActionSurvives() {
        long id = createEvent(D3, "INTERVIEW_2", "二面");

        // 用户手动把提醒改到别的时间（跟任何日程都对不上）
        LocalDateTime manual = LocalDate.now().plusDays(1).atTime(8, 30);
        put("/app/jobs/" + jobId + "/crm", token, Map.of("nextActionAt", manual.toString()))
                .assertSuccess();

        delete(PATH + "/" + id, token).assertSuccess();

        assertThat(nextActionAt()).isEqualTo(manual);
    }

    @Test
    @DisplayName("★ 删除职位 → 它的日程被级联清掉（日历里不再出现）")
    void deletingJobCascadesEvents() {
        createEvent(D1, "INTERVIEW_1", "一面");
        createEvent(D3, "INTERVIEW_2", "二面");
        assertThat(listRange(0, 15)).hasSize(2);

        delete("/app/jobs/" + jobId, token).assertSuccess();

        assertThat(listRange(0, 15)).isEmpty();
    }

    // ------------------------------------------------------------ 辅助

    private Long createJob(String tk, String title, String company) {
        return post("/app/jobs", tk, Map.of(
                "title", title,
                "companyName", company,
                "jobDescription", "任职要求：Java、MySQL、Kubernetes")).assertSuccess().longId();
    }

    private long createEvent(int daysAhead, String type, String title) {
        return post(PATH, token, eventBody(jobId, type, title, tomorrowAt(daysAhead, 10, 0),
                null, null, null, null)).assertSuccess().longId();
    }

    private Map<String, Object> eventBody(Long jid, String type, String title, LocalDateTime at,
                                          Integer duration, String mode, String location, String note) {
        return eventBodyRaw(String.valueOf(jid), type, title, at, duration, mode, location, note, null);
    }

    /** 带状态的重载 —— 改状态 / 标记完成时用 */
    private Map<String, Object> eventBody(Long jid, String type, String title, LocalDateTime at,
                                          Integer duration, String mode, String location, String note,
                                          String status) {
        return eventBodyRaw(String.valueOf(jid), type, title, at, duration, mode, location, note, status);
    }

    /** jobId 传原始字符串（用来构造「非数字」「不传」这类非法输入） */
    private Map<String, Object> eventBodyRaw(String jid, String type, String title, LocalDateTime at) {
        return eventBodyRaw(jid, type, title, at, null, null, null, null, null);
    }

    private Map<String, Object> eventBodyRaw(String jid, String type, String title, LocalDateTime at,
                                             Integer duration, String mode, String location, String note) {
        return eventBodyRaw(jid, type, title, at, duration, mode, location, note, null);
    }

    private Map<String, Object> eventBodyRaw(String jid, String type, String title, LocalDateTime at,
                                             Integer duration, String mode, String location, String note,
                                             String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jid);
        body.put("eventType", type);
        body.put("title", title);
        body.put("eventAt", at == null ? null : at.toString());
        body.put("durationMinutes", duration);
        body.put("eventMode", mode);
        body.put("location", location);
        body.put("note", note);
        body.put("status", status);
        return body;
    }

    /** +n 天 10:00 —— 用相对时间，测试不会因为跑在哪天而失败 */
    private LocalDateTime tomorrowAt(int daysAhead, int hour, int minute) {
        return LocalDate.now().plusDays(daysAhead).atTime(hour, minute);
    }

    private LocalDateTime tomorrowAt(int hour, int minute) {
        return LocalDate.now().plusDays(1).atTime(hour, minute);
    }

    private JsonNode listRange(int fromDays, int toDays) {
        LocalDate from = LocalDate.now().plusDays(fromDays);
        LocalDate to = LocalDate.now().plusDays(toDays);
        return get(PATH + "?from=" + from + "&to=" + to, token).assertSuccess().data();
    }

    /** 造第二个用户并登录 —— 测归属校验用 */
    private String loginAs(String username) {
        createAccount(username);
        return loginRaw("/app/auth/login", username, TEST_PASSWORD)
                .assertSuccess().data().path("accessToken").asText();
    }

    /** 直接读库 —— next_action_at 是派生值，从接口绕一圈反而看不清 */
    private LocalDateTime nextActionAt() {
        return jdbc.queryForObject("SELECT next_action_at FROM job WHERE id = ?",
                LocalDateTime.class, jobId);
    }
}
