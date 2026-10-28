package com.zest.mindforgeai.service;

/**
 * 日程主动提醒。
 *
 * @author crx
 * @since 2026-10-28
 */
public interface ReminderService {

    /**
     * 扫一轮「未来 {@code aheadHours} 小时内开始、还没提醒过」的日程，按用户聚合成邮件发出。
     * <p>
     * 可以重复调用：幂等由 {@code job_event.reminded_at} 的抢占式更新保证，
     * 不是靠「只调用一次」。
     *
     * @return 本轮成功发出的邮件封数（不是日程条数 —— 一个用户的多条日程合并成一封）
     */
    int remindUpcomingEvents();

    /**
     * 邮件通道是否可用。任务在开跑前先问这一句，没配 SMTP 就整轮跳过，
     * 免得把每一条日程的失败次数白白烧掉。
     */
    boolean isMailChannelReady();
}
