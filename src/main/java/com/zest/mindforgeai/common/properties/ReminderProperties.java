package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 日程提醒配置，对应 {@code mindforge.reminder.*}。
 * <p>
 * ★ 和 {@code mindforge.verification.*} 刻意分开：那组是「防刷闸门」（发码频率、试错次数），
 * 这组是「主动推送」的参数，两者语义完全不同，混在一起以后改一个会误伤另一个。
 *
 * @author crx
 * @since 2026-10-28
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.reminder")
public class ReminderProperties {

    /**
     * 提前多少小时提醒。
     * <p>
     * ★ 只做一次，不做「提前 1 小时」：一天收两封提醒同一场面试的邮件，
     * 用户第一反应是去关掉提醒开关，功能就白做了。二期要加也是「用户自选」而不是默认叠加。
     */
    private int aheadHours = 24;

    /**
     * 同一封邮件最多重试几次，达到后放弃。
     * <p>
     * 对应 {@code job_event.remind_fail_count}。不设上限的话，一个永久失效的邮箱
     * 会被每小时重试一次、永远重试下去。
     */
    private int maxFail = 3;

    /**
     * 发件人显示名。地址固定取 {@code spring.mail.username} ——
     * QQ 邮箱不允许用别的地址发信（会 553），所以只有显示名可配。
     */
    private String fromName = "MindForge AI";

    /**
     * 前端站点地址，用于邮件里「打开日历」的链接。
     * <p>
     * ⚠️ 生产必须配成真实域名，否则用户点开是 localhost —— 一封点不动的提醒邮件等于没提醒。
     */
    private String siteUrl = "http://localhost:5173";

    /**
     * 日历页路径。用户端的日历是「职位」页里的一个视图（{@code apps/user/src/router} 里没有独立路由）。
     * 前端改路由时要同步这里，否则链接会落到 404。
     */
    private String calendarPath = "/jobs";
}
