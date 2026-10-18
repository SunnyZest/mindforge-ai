package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 验证码配置，对应 {@code mindforge.verification.*}。
 * <p>
 * ★ 这些值<b>都是防刷的闸门</b>，不是「体验参数」：
 * 发码接口必须公开（未登录才需要发码），所以它天生就是「邮件轰炸」的入口。
 * 改大任何一个数字，等于把闸门开大。
 *
 * @author crx
 * @since 2026-10-18
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.verification")
public class VerificationProperties {

    /** 验证码位数。6 位是「够短好输入」与「够长不易猜」的平衡点 */
    private int codeLength = 6;

    /** 有效期（分钟）。太长会给爆破留时间窗口，太短用户来不及收信 */
    private int ttlMinutes = 10;

    /** 重发冷却（秒）。防连点 */
    private int cooldownSeconds = 60;

    /** 同一目标每天最多发几条（邮件 10 条，短信要更少） */
    private int dailyLimit = 10;

    /** 同一目标最多试错几次，超了这条码直接作废（防爆破 6 位码） */
    private int maxAttempts = 5;

    /** 发件人显示名。地址固定用 {@code spring.mail.username} —— QQ 邮箱不允许伪造 From */
    private String fromName = "MindForge AI";
}
