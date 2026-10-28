package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.entity.JobEvent;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日程主动提醒。
 *
 * <p>重点验证三件事，都是「出问题用户会直接感知」的：
 * <ol>
 *   <li><b>幂等</b>：同一批日程跑两轮只发一封。靠的是 {@code reminded_at} 抢占，不是调用次数；</li>
 *   <li><b>该发的不漏</b>：24 小时窗口内的 PENDING 日程要发；</li>
 *   <li><b>不该发的不发</b>：已开始 / 窗口外 / 已完成 / 已删除 / 关开关 / 邮箱没验证，一律跳过。</li>
 * </ol>
 *
 * <p>发信本身被 mock 掉（见 {@link AbstractApiTest}），这里只关心「有没有决定要发」和「发了之后数据长什么样」。
 *
 * @author crx
 * @since 2026-10-28
 */
class ReminderServiceTest extends AbstractApiTest {

    private static final long JOB_ID = 991001L;

    @Autowired
    private ReminderService reminderService;

    private Long userId;

    @BeforeEach
    void setUpReminder() {
        userId = userId(USER_NAME);
        // 基类造的测试账号邮箱默认是「未验证」的，先补成「已验证 + 开着提醒」。
        // ⚠️ 这里改的是共享账号 t_user，改动会残留到下一个用例 ——
        //    基类的 ensureAccount 每次 @BeforeEach 会把邮箱 / 验证状态 / 开关归一回去，
        //    所以整包跑也不会污染别的用例（实测踩过：不加归一的话 UserProfileApiTest
        //    有 3 个断言 t_user 邮箱的用例会挂，且只在整包跑时挂）。
        jdbc.update("UPDATE `user` SET email = ?, email_verified = 1, reminder_enabled = 1 WHERE id = ?",
                "reminder@test.local", userId);
        jdbc.update("INSERT INTO job (id, user_id, title, raw_content) VALUES (?, ?, ?, ?)",
                JOB_ID, userId, "后端开发工程师", "JD");

        when(reminderMailSender.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("24 小时窗口内的多条日程合并成一封邮件，并写入 reminded_at")
    void sendsOneMailForAllUpcomingEvents() {
        long first = insertEvent(992001L, LocalDateTime.now().plusHours(2), "PENDING", 0);
        long second = insertEvent(992002L, LocalDateTime.now().plusHours(20), "PENDING", 0);

        int sent = reminderService.remindUpcomingEvents();

        assertThat(sent).isEqualTo(1);
        ArgumentCaptor<List<JobEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(reminderMailSender).sendReminder(any(), captor.capture());
        // 两场面试合成一封，按时间升序 —— 用户从上往下看就是时间顺序
        assertThat(captor.getValue()).extracting(JobEvent::getId)
                .containsExactly(first, second);
        assertThat(remindedAtOf(first)).isNotNull();
        assertThat(remindedAtOf(second)).isNotNull();
    }

    @Test
    @DisplayName("幂等：同一批日程跑两轮只发一封")
    void doesNotResendOnSecondRun() {
        insertEvent(992003L, LocalDateTime.now().plusHours(5), "PENDING", 0);

        assertThat(reminderService.remindUpcomingEvents()).isEqualTo(1);
        assertThat(reminderService.remindUpcomingEvents()).isZero();

        verify(reminderMailSender, times(1)).sendReminder(any(), anyList());
    }

    @Test
    @DisplayName("已经开始的、超出窗口的、已完成的、已删除的一律不提醒")
    void ignoresEventsThatDoNotQualify() {
        insertEvent(992011L, LocalDateTime.now().minusHours(1), "PENDING", 0);   // 已经开始了
        insertEvent(992012L, LocalDateTime.now().plusHours(30), "PENDING", 0);  // 超出 24 小时
        insertEvent(992013L, LocalDateTime.now().plusHours(3), "DONE", 0);      // 已完成
        insertEvent(992014L, LocalDateTime.now().plusHours(4), "CANCELLED", 0); // 已取消
        insertEvent(992015L, LocalDateTime.now().plusHours(6), "PENDING", 1);   // 已删除

        assertThat(reminderService.remindUpcomingEvents()).isZero();
        verify(reminderMailSender, never()).sendReminder(any(), anyList());
    }

    @Test
    @DisplayName("用户关掉提醒开关后不再收到邮件")
    void skipsUserWhoTurnedOffReminder() {
        insertEvent(992021L, LocalDateTime.now().plusHours(3), "PENDING", 0);
        jdbc.update("UPDATE `user` SET reminder_enabled = 0 WHERE id = ?", userId);

        assertThat(reminderService.remindUpcomingEvents()).isZero();
        verify(reminderMailSender, never()).sendReminder(any(), anyList());
        assertThat(remindedAtOf(992021L)).isNull();
    }

    @Test
    @DisplayName("邮箱没验证的用户不发 —— 没验证过的地址大概率是打错的")
    void skipsUnverifiedEmail() {
        insertEvent(992031L, LocalDateTime.now().plusHours(3), "PENDING", 0);
        jdbc.update("UPDATE `user` SET email_verified = 0 WHERE id = ?", userId);

        assertThat(reminderService.remindUpcomingEvents()).isZero();
        verify(reminderMailSender, never()).sendReminder(any(), anyList());
    }

    @Test
    @DisplayName("发信失败要回滚 reminded_at 并累计失败次数，失败 3 次后放弃")
    void rollsBackAndGivesUpAfterMaxFailures() {
        long id = insertEvent(992041L, LocalDateTime.now().plusHours(4), "PENDING", 0);
        doThrow(new MailSendException("smtp down"))
                .when(reminderMailSender).sendReminder(any(), anyList());

        assertThat(reminderService.remindUpcomingEvents()).isZero();
        // 回滚了才可能重试：reminded_at 必须回到 NULL
        assertThat(remindedAtOf(id)).isNull();
        assertThat(failCountOf(id)).isEqualTo(1);

        // 再失败两次，到达上限 3
        reminderService.remindUpcomingEvents();
        reminderService.remindUpcomingEvents();
        assertThat(failCountOf(id)).isEqualTo(3);

        // 第 4 轮不该再尝试 —— 否则一个永久失效的邮箱会被每小时重试到天荒地老
        assertThat(reminderService.remindUpcomingEvents()).isZero();
        verify(reminderMailSender, times(3)).sendReminder(any(), anyList());
    }

    @Test
    @DisplayName("邮件通道没配置时整轮跳过，不消耗失败次数")
    void skipsWholeRoundWhenMailNotConfigured() {
        long id = insertEvent(992051L, LocalDateTime.now().plusHours(4), "PENDING", 0);
        when(reminderMailSender.isConfigured()).thenReturn(false);

        assertThat(reminderService.remindUpcomingEvents()).isZero();

        verify(reminderMailSender, never()).sendReminder(any(), anyList());
        // ★ 关键：失败次数必须还是 0。被烧掉的话，等运维配好邮箱这些日程已经到上限不再重试了
        assertThat(failCountOf(id)).isZero();
        assertThat(remindedAtOf(id)).isNull();
    }

    @Test
    @DisplayName("改期后重新进入待提醒状态 —— 今天面完改到下周不能就此沉默")
    void rescheduleResetsReminderState() {
        long id = insertEvent(992061L, LocalDateTime.now().plusHours(3), "PENDING", 0);
        // 先让它「已经提醒过」，并带一次失败记录
        jdbc.update("UPDATE job_event SET reminded_at = NOW(), remind_fail_count = 2 WHERE id = ?", id);

        String token = loginAsUser();
        LocalDateTime newTime = LocalDateTime.now().plusDays(7).withNano(0);
        ApiResult result = put("/app/calendar/events/" + id, token, Map.of(
                "jobId", String.valueOf(JOB_ID),
                "eventType", "INTERVIEW_1",
                "title", "字节跳动 技术一面",
                "eventAt", newTime.toString()));

        assertThat(result.code()).isZero();
        assertThat(remindedAtOf(id)).isNull();
        assertThat(failCountOf(id)).isZero();
    }

    // ---------------------------------------------------------------- 工具

    private long insertEvent(long id, LocalDateTime eventAt, String status, int deleted) {
        jdbc.update("""
                INSERT INTO job_event (id, user_id, job_id, event_type, title, event_at,
                                       status, reminded_at, remind_fail_count, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 0, ?)
                """, id, userId, JOB_ID, "INTERVIEW_1", "字节跳动 技术一面", eventAt, status, deleted);
        return id;
    }

    private LocalDateTime remindedAtOf(long eventId) {
        return jdbc.queryForObject("SELECT reminded_at FROM job_event WHERE id = ?",
                LocalDateTime.class, eventId);
    }

    private Integer failCountOf(long eventId) {
        return jdbc.queryForObject("SELECT remind_fail_count FROM job_event WHERE id = ?",
                Integer.class, eventId);
    }
}
