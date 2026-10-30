package com.zest.mindforgeai.common.constant;

/**
 * JWT 常量
 *
 * @author crx
 * @since 2026-10-03
 */
public final class JwtConstants {

    private JwtConstants() {
    }

    /** 自定义 claim：用户名 */
    public static final String CLAIM_USERNAME = "username";

    /** 自定义 claim：角色 code 列表 */
    public static final String CLAIM_ROLES = "roles";

    /**
     * 自定义 claim：会话 id。
     * <p>
     * 有了它，「我的登录设备」列表才能标出「本机」，踢其他设备时也知道该保留哪个。
     * 存量 token（加这个 claim 之前签发的）没有它，解析出来是 null —— 调用方要当心。
     */
    public static final String CLAIM_SESSION_ID = "sid";
}
