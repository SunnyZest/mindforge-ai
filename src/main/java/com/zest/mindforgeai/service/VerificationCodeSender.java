package com.zest.mindforgeai.service;

/**
 * 验证码发送通道。
 * <p>
 * ★ 抽象这一层的原因：验证码的 90% 逻辑（生成、存储、限流、校验、一次性消费）
 * 与通道无关，只有「怎么发出去」不同。所以邮件和短信各写一个实现，核心逻辑一份。
 * <p>
 * ⚠️ 实现方<b>不能</b>把验证码打进日志 —— 它是 10 分钟内有效的凭据。
 *
 * @author crx
 * @since 2026-10-18
 */
public interface VerificationCodeSender {

    /** 对应 {@code VerificationConstants.CHANNEL_*} */
    String channel();

    /**
     * 发出验证码。
     *
     * @param target 收件邮箱（SMS 时是手机号）
     * @param code   验证码原文
     * @param scene  {@code REGISTER} / {@code RESET_PASSWORD}，用来决定文案
     * @throws com.zest.mindforgeai.common.exception.BizException 通道未配置或发送失败
     */
    void send(String target, String code, String scene);
}
