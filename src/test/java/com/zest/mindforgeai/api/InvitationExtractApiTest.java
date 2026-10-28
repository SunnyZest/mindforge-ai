package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 面试邀约抽取（{@code POST /app/calendar/invitations/extract}）。
 * <p>
 * 这个接口的<b>重点不是「能不能调通 AI」，而是「模型乱说时会不会被放过去」</b>，
 * 所以用例大部分是在喂<b>故意有问题的模型输出</b>，断言后端把它拦住了：
 * <ol>
 *   <li><b>证据闸</b>：字段值有、但支撑它的原文片段在原文里找不到 → 该字段必须变成 null；</li>
 *   <li><b>枚举与时间钳制</b>：白名单外的类型、解析不了的时间，一律 null，不猜；</li>
 *   <li><b>职位匹配</b>：唯一命中才认，有歧义就返回 null 让用户自己选；</li>
 *   <li><b>限流</b>：超过每小时 20 次要拒绝。</li>
 * </ol>
 * AI 调用本身被 mock（基类 {@code AbstractApiTest} 里已经把 {@code AiServiceClient} 整个换成了 mock），
 * 否则用例要等真实模型、还会烧额度。
 *
 * @author crx
 * @since 2026-10-28
 */
class InvitationExtractApiTest extends AbstractApiTest {

    private static final String PATH = "/app/calendar/invitations/extract";

    /**
     * 一段贴近真实的邀约原文。
     * <p>
     * ★ 里面刻意<b>没有</b>「腾讯科技」「二面」这些词 —— 用例要拿它们当
     * 「模型编出来的内容」，验证证据闸能不能拦住。
     */
    private static final String INVITATION_TEXT = """
            您好，我是字节跳动 HR，邀请您参加「后端开发工程师」岗位的技术一面。
            时间：2026 年 11 月 4 日 14:00，预计 1 小时。
            形式：线上，腾讯会议 https://meeting.tencent.com/dm/AbCd1234
            面试官：李老师
            """;

    private String token;

    @BeforeEach
    void setUpToken() {
        token = loginAsUser();
    }

    // ---------- 证据闸 ----------

    @Test
    @DisplayName("原文里找不到依据的字段被丢弃，有依据的保留")
    void dropsFieldsWhoseEvidenceIsNotInSource() {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("title", "字节跳动 HR");                  // 原文里有
        evidence.put("companyName", "腾讯科技");               // 原文里没有 —— 编的
        evidence.put("eventAt", "2026 年 11 月 4 日 14:00");   // 原文里有
        evidence.put("eventType", "技术一面");                 // 原文里有
        evidence.put("durationMinutes", "1 小时");             // 原文里有
        evidence.put("eventMode", "线上");                     // 原文里有

        stub(extracted("字节跳动 后端开发工程师 技术一面", "字节跳动", "后端开发工程师",
                "INTERVIEW_1", "2026-11-04T14:00", false, 60, "ONLINE", null, evidence));

        ApiResult result = extractText(INVITATION_TEXT).assertSuccess();
        JsonNode event = result.data().path("events").get(0);

        assertThat(event.path("title").asText()).isEqualTo("字节跳动 后端开发工程师 技术一面");
        // 公司名的证据在原文里不存在 → 必须被丢掉，而不是「模型说了就算」
        assertThat(event.path("companyName").isNull()).isTrue();
        assertThat(event.path("eventAt").asText()).isEqualTo("2026-11-04T14:00:00");
        assertThat(event.path("eventType").asText()).isEqualTo("INTERVIEW_1");
        assertThat(event.path("durationMinutes").asInt()).isEqualTo(60);
        assertThat(event.path("eventMode").asText()).isEqualTo("ONLINE");

        // 证据 map 里也不该再出现被丢弃的字段
        assertThat(event.path("evidence").has("companyName")).isFalse();
        assertThat(event.path("evidence").path("title").asText()).isEqualTo("字节跳动 HR");

        // 用户要被告知「有内容被丢了」，否则他会以为原文里就是没写公司
        assertThat(result.data().path("warning").asText()).contains("找不到依据");
    }

    @Test
    @DisplayName("没有 evidence 的字段一律丢弃（没证据 = 没说过）")
    void dropsFieldsWithoutEvidence() {
        stub(extracted("技术一面", "字节跳动", "后端开发工程师", "INTERVIEW_1",
                "2026-11-04T14:00", false, null, null, null, Map.of("title", "字节跳动 HR")));

        ApiResult result = extractText(INVITATION_TEXT).assertSuccess();
        JsonNode event = result.data().path("events").get(0);

        assertThat(event.path("title").asText()).isEqualTo("技术一面");
        assertThat(event.path("companyName").isNull()).isTrue();
        assertThat(event.path("eventAt").isNull()).isTrue();
        assertThat(event.path("eventType").isNull()).isTrue();
    }

    @Test
    @DisplayName("全部字段都被丢弃时，提示文案说清是「找不到依据」而不是「没识别出日程」")
    void allDroppedGivesAccurateWarning() {
        stub(extracted("字节跳动 技术一面", "字节跳动", "后端开发工程师", "INTERVIEW_1",
                "2026-11-04T14:00", false, null, null, null, Map.of()));

        ApiResult result = extractText(INVITATION_TEXT).assertSuccess();
        JsonNode event = result.data().path("events").get(0);

        assertThat(event.path("title").isNull()).isTrue();
        assertThat(result.data().path("warning").asText()).contains("找不到依据");
    }

    // ---------- 枚举与时间钳制 ----------

    @Test
    @DisplayName("白名单外的日程类型置空，不猜")
    void unknownEventTypeBecomesNull() {
        stub(extracted("技术一面", null, null, "TECH_ROUND",
                "2026-11-04T14:00", false, null, "ONSITE_MEETING", null,
                Map.of("title", "技术一面", "eventType", "技术一面",
                        "eventAt", "2026 年 11 月 4 日 14:00",
                        "eventMode", "线上")));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        assertThat(event.path("eventType").isNull()).isTrue();
        assertThat(event.path("eventMode").isNull()).isTrue();
    }

    @Test
    @DisplayName("相对表述换算出来的时间标 derived，并提示用户核对")
    void derivedTimeIsFlagged() {
        stub(extracted("技术一面", null, null, "INTERVIEW_1",
                "2026-11-04T14:00", true, null, null, null,
                Map.of("title", "技术一面", "eventAt", "2026 年 11 月 4 日 14:00")));

        ApiResult result = extractText(INVITATION_TEXT).assertSuccess();
        JsonNode event = result.data().path("events").get(0);

        assertThat(event.path("eventAtDerived").asBoolean()).isTrue();
        assertThat(result.data().path("warning").asText()).contains("推算");
    }

    @Test
    @DisplayName("带 Z 的 UTC 时间换算成北京时间 —— 直接取本地部分会差 8 小时")
    void utcTimeIsConvertedToShanghai() {
        // ICS 里的事件时间是 UTC：06:00Z 就是北京时间 14:00。
        // 直接 toLocalDateTime() 会得到 06:00，表现为「提醒提前 8 小时发出去」。
        stub(extracted("技术一面", null, null, null, "2026-11-04T06:00:00Z", false, null, null, null,
                Map.of("title", "技术一面", "eventAt", "2026 年 11 月 4 日 14:00")));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        assertThat(event.path("eventAt").asText()).isEqualTo("2026-11-04T14:00:00");
    }

    @Test
    @DisplayName("带 +08:00 偏移的时间同样归到北京时间")
    void offsetTimeIsNormalized() {
        stub(extracted("技术一面", null, null, null, "2026-11-04T09:00:00+09:00", false, null, null, null,
                Map.of("title", "技术一面", "eventAt", "2026 年 11 月 4 日 14:00")));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        // 东京时间 09:00 = 北京时间 08:00
        assertThat(event.path("eventAt").asText()).isEqualTo("2026-11-04T08:00:00");
    }

    @Test
    @DisplayName("时间解析不了就当没写，同时提示需要手填")
    void unparsableTimeBecomesNull() {
        stub(extracted("技术一面", null, null, "INTERVIEW_1",
                "下周三下午两点左右", false, null, null, null,
                Map.of("title", "技术一面", "eventAt", "2026 年 11 月 4 日 14:00")));

        ApiResult result = extractText(INVITATION_TEXT).assertSuccess();
        JsonNode event = result.data().path("events").get(0);

        assertThat(event.path("eventAt").isNull()).isTrue();
        // 时间没了，derived 也不该是 true —— 否则前端会对着空时间显示「已换算」
        assertThat(event.path("eventAtDerived").asBoolean()).isFalse();
        assertThat(result.data().path("warning").asText()).contains("没识别出时间");
    }

    @Test
    @DisplayName("时长越界（0 或超过 24 小时）置空")
    void outOfRangeDurationBecomesNull() {
        stub(extracted("技术一面", null, null, null, null, false, 5000, null, null,
                Map.of("title", "技术一面", "durationMinutes", "1 小时")));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        assertThat(event.path("durationMinutes").isNull()).isTrue();
    }

    // ---------- 职位匹配 ----------

    @Test
    @DisplayName("公司名对上唯一一个职位时自动匹配")
    void matchesSingleJobByCompany() {
        long jobId = insertJob("字节跳动", "后端开发工程师");

        stub(extracted("字节跳动 技术一面", "字节跳动", "后端开发工程师", "INTERVIEW_1",
                "2026-11-04T14:00", false, null, null, null,
                Map.of("title", "字节跳动 HR", "companyName", "字节跳动",
                        "positionName", "后端开发工程师",
                        "eventAt", "2026 年 11 月 4 日 14:00")));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        assertThat(event.path("matchedJobId").asText()).isEqualTo(String.valueOf(jobId));
        assertThat(event.path("matchedJobTitle").asText()).isEqualTo("后端开发工程师");
    }

    @Test
    @DisplayName("同一家公司有两个职位、岗位又对不上时，不猜，返回 null 让用户选")
    void ambiguousCompanyIsNotMatched() {
        insertJob("字节跳动", "前端开发工程师");
        insertJob("字节跳动", "数据分析师");

        stub(extracted("字节跳动 技术一面", "字节跳动", "后端开发工程师", "INTERVIEW_1",
                "2026-11-04T14:00", false, null, null, null,
                Map.of("title", "字节跳动 HR", "companyName", "字节跳动",
                        "positionName", "后端开发工程师",
                        "eventAt", "2026 年 11 月 4 日 14:00")));

        ApiResult result = extractText(INVITATION_TEXT).assertSuccess();
        JsonNode event = result.data().path("events").get(0);

        assertThat(event.path("matchedJobId").isNull()).isTrue();
        assertThat(result.data().path("warning").asText()).contains("没匹配到已有职位");
    }

    @Test
    @DisplayName("公司名带法律后缀也能对上（「北京字节跳动科技有限公司」 vs 「字节跳动」）")
    void matchesCompanyIgnoringLegalSuffix() {
        long jobId = insertJob("北京字节跳动科技有限公司", "后端开发工程师");

        stub(extracted("字节跳动 技术一面", "字节跳动", null, null, null, false, null, null, null,
                Map.of("title", "字节跳动 HR", "companyName", "字节跳动")));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        assertThat(event.path("matchedJobId").asText()).isEqualTo(String.valueOf(jobId));
    }

    // ---------- 会议链接 ----------

    @Test
    @DisplayName("原文里的会议链接保留")
    void keepsMeetingUrlFromSource() {
        stub(withMeetingUrl("https://meeting.tencent.com/dm/AbCd1234"));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        assertThat(event.path("meetingUrl").asText()).isEqualTo("https://meeting.tencent.com/dm/AbCd1234");
        // 面试官不在证据契约里，只做长度截断，照常透传
        assertThat(event.path("interviewer").asText()).isEqualTo("李老师");
    }

    @Test
    @DisplayName("原文里没有的会议链接被丢弃，避免用户进错会")
    void dropsFabricatedMeetingUrl() {
        stub(withMeetingUrl("https://zoom.us/j/999999"));

        JsonNode event = extractText(INVITATION_TEXT).assertSuccess().data().path("events").get(0);

        assertThat(event.path("meetingUrl").isNull()).isTrue();
    }

    // ---------- 空结果与文件输入 ----------    @Test
    @DisplayName("原文里没有日程时返回空数组并给出提示，而不是报错")
    void emptyEventsReturnsHint() {
        stub(new AiServiceClient.InvitationExtractData(INVITATION_TEXT, List.of(), false));

        ApiResult result = extractText(INVITATION_TEXT).assertSuccess();

        assertThat(result.data().path("events")).isEmpty();
        assertThat(result.data().path("sourceLength").asInt()).isPositive();
        assertThat(result.data().path("warning").asText()).contains("没有识别出明确的日程");
    }

    @Test
    @DisplayName("文件输入时 Python 没回传原文 → 证据闸失效，直接报错而不是放行")
    void fileSourceWithoutSourceTextFails() {
        stub(new AiServiceClient.InvitationExtractData(null, List.of(), false));

        post(PATH, token, Map.of(
                "source", "FILE",
                "fileType", "PDF",
                "contentBase64", "JVBERi0xLjQK")).assertCode(50201);
    }

    @Test
    @DisplayName("不支持的文件类型被拦在解析之前")
    void unsupportedFileTypeRejected() {
        post(PATH, token, Map.of(
                "source", "FILE",
                "fileType", "XLSX",
                "contentBase64", "JVBERi0xLjQK")).assertCode(42201);
    }

    @Test
    @DisplayName("粘贴文本模式必须带内容")
    void textSourceRequiresText() {
        post(PATH, token, Map.of("source", "TEXT", "text", "   ")).assertCode(40001);
    }

    // ---------- 限流 ----------

    @Test
    @DisplayName("每小时超过 20 次被拒绝")
    void quotaExceeded() {
        stub(new AiServiceClient.InvitationExtractData(INVITATION_TEXT, List.of(), false));
        redis.opsForValue().set("mf:invitation:extract:u:" + userId(USER_NAME), "20");

        extractText(INVITATION_TEXT).assertCode(42901);
    }

    // ---------- 辅助 ----------

    private ApiResult extractText(String text) {
        return post(PATH, token, Map.of("source", "TEXT", "text", text));
    }

    private void stub(AiServiceClient.InvitationExtractData data) {
        when(aiServiceClient.extractInvitation(any())).thenReturn(data);
    }

    /** 常用形态：原文就是 {@link #INVITATION_TEXT}，只是候选日程不同 */
    private void stub(AiServiceClient.ExtractedEvent... events) {
        stub(new AiServiceClient.InvitationExtractData(INVITATION_TEXT, List.of(events), false));
    }

    private long insertJob(String companyName, String title) {
        long id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("""
                INSERT INTO job (id, user_id, title, company_name, raw_content, status, deleted)
                VALUES (?, ?, ?, ?, ?, 1, 0)
                """, id, userId(USER_NAME), title, companyName, "JD");
        return id;
    }

    /**
     * 造一条模型输出。
     * <p>
     * 参数按「字段多、但大多可空」来排，调用处用 {@code null} 表示「模型没抽到」，
     * 和真实返回的形状一致。
     */
    /** 只关心会议链接与面试官的形态：其余字段为空，证据只给 title */
    private AiServiceClient.ExtractedEvent withMeetingUrl(String url) {
        return new AiServiceClient.ExtractedEvent("技术一面", null, null, null, null, false,
                null, null, null, url, "李老师", null, Map.of("title", "技术一面"));
    }

    private AiServiceClient.ExtractedEvent extracted(String title,                                                     String companyName,
                                                     String positionName,
                                                     String eventType,
                                                     String eventAt,
                                                     Boolean eventAtDerived,
                                                     Integer durationMinutes,
                                                     String eventMode,
                                                     String location,
                                                     Map<String, String> evidence) {
        return new AiServiceClient.ExtractedEvent(title, companyName, positionName, eventType,
                eventAt, eventAtDerived, durationMinutes, eventMode, location,
                null, null, null, evidence);
    }
}
