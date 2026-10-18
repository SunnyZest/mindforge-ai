package com.zest.mindforgeai.service;

/**
 * 邮箱 / 短信验证码。
 *
 * @author crx
 * @since 2026-10-18
 */
public interface VerificationCodeService {

    /**
     * 生成并发送验证码。
     * <p>
     * 受三层闸门约束：60 秒重发冷却、每日条数上限、同一目标同一场景只保留最新一条。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         {@code VERIFICATION_TOO_FREQUENT} 太频繁 / {@code EMAIL_SEND_FAILED} 通道失败
     */
    void send(String channel, String target, String scene);

    /**
     * 校验并<b>消费</b>验证码（一次性）。
     * <p>
     * ⚠️ 消费是原子的：并发两个请求同时带同一个码，只有一个能成功。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException {@code VERIFICATION_CODE_INVALID}
     *         码错 / 过期 / 试错超限 / 已被用掉 —— 都合并成同一个错误码，不给攻击者区分的机会
     */
    void verifyAndConsume(String channel, String target, String scene, String code);
}
