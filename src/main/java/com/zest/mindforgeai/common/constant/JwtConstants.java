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
}
