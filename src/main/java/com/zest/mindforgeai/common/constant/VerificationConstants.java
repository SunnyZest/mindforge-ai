package com.zest.mindforgeai.common.constant;

import java.util.Set;

/**
 * 验证码的通道、场景与 Redis key 前缀。
 * <p>
 * ★ 用常量而不是枚举，跟项目里文档状态（{@code DocumentStatusUtils}）保持一致 ——
 * 这些值同时会进 Redis key 和接口入参，字符串反而更好排查。
 *
 * @author crx
 * @since 2026-10-18
 */
public final class VerificationConstants {

    private VerificationConstants() {
    }

    /* ---------- 通道 ---------- */

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_SMS = "SMS";

    /**
     * 当前已实现的通道。
     * <p>
     * ⚠️ SMS 只是预留：{@link #CHANNELS} 里加它，但没有对应的 Sender 实现，
     * 调用会明确报「通道未配置」，而不是静默发不出去。
     */
    public static final Set<String> CHANNELS = Set.of(CHANNEL_EMAIL, CHANNEL_SMS);

    /* ---------- 场景 ---------- */

    public static final String SCENE_REGISTER = "REGISTER";
    public static final String SCENE_RESET_PASSWORD = "RESET_PASSWORD";

    public static final Set<String> SCENES = Set.of(SCENE_REGISTER, SCENE_RESET_PASSWORD);

    /* ---------- Redis key ---------- */

    /** {@code mf:code:{channel}:{scene}:{target}} —— hash{code, attempts}，TTL = 有效期 */
    public static final String PREFIX_CODE = "mf:code:";

    /** {@code mf:code:cd:{channel}:{target}} —— 存在即处于冷却中 */
    public static final String PREFIX_COOLDOWN = "mf:code:cd:";

    /** {@code mf:code:day:{channel}:{target}} —— 当天已发条数 */
    public static final String PREFIX_DAILY = "mf:code:day:";

    public static String codeKey(String channel, String scene, String target) {
        return PREFIX_CODE + channel + ":" + scene + ":" + target;
    }

    public static String cooldownKey(String channel, String target) {
        return PREFIX_COOLDOWN + channel + ":" + target;
    }

    public static String dailyKey(String channel, String target) {
        return PREFIX_DAILY + channel + ":" + target;
    }
}
