package com.zest.mindforgeai.common.constant;

/**
 * 审计日志常量：统一「动作 / 资源类型 / 结果」的取值，避免各处手写字符串。
 *
 * @author crx
 * @since 2026-10-03
 */
public final class AuditConstants {

    private AuditConstants() {
    }

    /* ---------- 操作结果 ---------- */

    /** 成功 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    /** 失败 */
    public static final String RESULT_FAIL = "FAIL";

    /* ---------- 操作动作 ---------- */

    /** 管理员登录 */
    public static final String ACTION_ADMIN_LOGIN = "ADMIN_LOGIN";

    /** 用户登录 */
    public static final String ACTION_USER_LOGIN = "USER_LOGIN";

    /** 用户注册 */
    public static final String ACTION_USER_REGISTER = "USER_REGISTER";

    /** 给用户分配角色 */
    public static final String ACTION_USER_ASSIGN_ROLE = "USER_ASSIGN_ROLE";

    /** 创建角色 */
    public static final String ACTION_ROLE_CREATE = "ROLE_CREATE";

    /** 修改角色 */
    public static final String ACTION_ROLE_UPDATE = "ROLE_UPDATE";

    /** 删除角色 */
    public static final String ACTION_ROLE_DELETE = "ROLE_DELETE";

    /** 给角色分配权限 */
    public static final String ACTION_ROLE_ASSIGN_PERMISSION = "ROLE_ASSIGN_PERMISSION";

    /** 修改 AI 配置 */
    public static final String ACTION_AI_CONFIG_UPDATE = "AI_CONFIG_UPDATE";

    /** 越权访问被拒绝（403） */
    public static final String ACTION_ACCESS_DENIED = "ACCESS_DENIED";

    /* ---------- 资源类型 ---------- */

    public static final String RESOURCE_USER = "USER";
    public static final String RESOURCE_ROLE = "ROLE";
    public static final String RESOURCE_PERMISSION = "PERMISSION";
    public static final String RESOURCE_AI_CONFIG = "AI_CONFIG";

    /** API 资源：用于记录「被拒绝的请求路径」，resourceId 存 URI */
    public static final String RESOURCE_API = "API";
}
