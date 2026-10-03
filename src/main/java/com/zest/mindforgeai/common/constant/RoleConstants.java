package com.zest.mindforgeai.common.constant;

/**
 * 角色常量，对应 sys_role.code（见 mindforge-schema §11）
 *
 * @author crx
 * @since 2026-10-03
 */
public final class RoleConstants {

    private RoleConstants() {
    }

    /** Spring Security 角色前缀 */
    public static final String ROLE_PREFIX = "ROLE_";

    /** 用户端普通用户 */
    public static final String USER = "USER";

    /** 管理端管理员 */
    public static final String ADMIN = "ADMIN";

    /** 超级管理员 */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
}
