package com.zest.mindforgeai.task;

import com.zest.mindforgeai.service.ReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日程主动提醒任务：每小时扫一遍，把未来 24 小时内要开始的面试 / 笔试提前告诉用户。
 * <p>
 * <b>为什么是每小时，不是每天凌晨一次</b>：
 * 提前量固定 24 小时，如果一天只扫一次，那「明天 15:00 的面试」在今天的扫描时还没进入
 * 24 小时窗口，要等到明天凌晨才被扫到 —— 用户早上醒来才看到提醒，只剩几个小时。
 * 每小时扫一次，实际提醒时点就落在「开始前 23~24 小时」这个区间，误差最多一小时。
 * <p>
 * <b>为什么不用分布式锁</b>：
 * 幂等已经由 {@code job_event.reminded_at} 的抢占式更新保证了 —— 抢不到就跳过。
 * 多实例同时跑最多是白打一次 UPDATE，不会有重复邮件。
 * 这和清理类任务「删两次结果一样」是两种不同思路的幂等，但结论一致：都不需要锁。
 *
 * @author crx
 * @since 2026-10-28
 */
@Slf4j
@Component
public class ReminderTask {

    private final ReminderService reminderService;

    public ReminderTask(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /**
     * 每小时整点执行（cron 可通过 {@code mindforge.task.reminder-cron} 覆盖）。
     */
    @Scheduled(cron = "${mindforge.task.reminder-cron:0 0 * * * ?}")
    public void remindUpcomingEvents() {
        try {
            reminderService.remindUpcomingEvents();
        } catch (Exception e) {
            // 兜底：任务失败不影响后续调度，但必须留下日志 —— 否则「用户说没收到提醒」
            // 的时候没有任何线索可查
            log.error("日程提醒任务执行失败", e);
        }
    }
}
