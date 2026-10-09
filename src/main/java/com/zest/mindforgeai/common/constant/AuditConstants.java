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

    /** 管理员创建用户 */
    public static final String ACTION_USER_CREATE = "USER_CREATE";

    /** 管理员删除（逻辑）用户 */
    public static final String ACTION_USER_DELETE = "USER_DELETE";

    /** 用户修改自己的资料 */
    public static final String ACTION_USER_UPDATE_PROFILE = "USER_UPDATE_PROFILE";

    /** 用户修改密码（成功和失败都记 —— 拿着有效 token 却不知道原密码，是可疑信号） */
    public static final String ACTION_USER_CHANGE_PASSWORD = "USER_CHANGE_PASSWORD";

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

    /** 新增 / 删除 AI 模型配置。⚠️ 审计明细里绝不能带 API Key */
    public static final String ACTION_AI_CONFIG_CREATE = "AI_CONFIG_CREATE";
    public static final String ACTION_AI_CONFIG_DELETE = "AI_CONFIG_DELETE";

    /** 越权访问被拒绝（403） */
    public static final String ACTION_ACCESS_DENIED = "ACCESS_DENIED";

    /** 创建知识库 */
    public static final String ACTION_KB_CREATE = "KB_CREATE";

    /** 修改知识库 */
    public static final String ACTION_KB_UPDATE = "KB_UPDATE";

    /** 删除知识库 */
    public static final String ACTION_KB_DELETE = "KB_DELETE";

    /** 上传文档 */
    public static final String ACTION_DOCUMENT_UPLOAD = "DOCUMENT_UPLOAD";

    /** 删除文档 */
    public static final String ACTION_DOCUMENT_DELETE = "DOCUMENT_DELETE";

    /** 重试文档处理 */
    public static final String ACTION_DOCUMENT_RETRY = "DOCUMENT_RETRY";

    /** 管理员手工修改文档处理状态（能破坏数据，比重试危险，单独记一个 action） */
    public static final String ACTION_DOCUMENT_UPDATE = "DOCUMENT_UPDATE";

    /** 新建对话会话 */
    public static final String ACTION_CONVERSATION_CREATE = "CONVERSATION_CREATE";
    /** 删除对话会话（会级联清掉消息和引用） */
    public static final String ACTION_CONVERSATION_DELETE = "CONVERSATION_DELETE";
    /** 给回答打分 */
    public static final String ACTION_RAG_FEEDBACK = "RAG_FEEDBACK";

    /** 简历：上传（创建）与删除。解析/优化不记 —— 那两步是只读或只产生建议，不改数据 */
    public static final String ACTION_RESUME_CREATE = "RESUME_CREATE";
    public static final String ACTION_RESUME_DELETE = "RESUME_DELETE";

    /**
     * 触发简历解析。
     * <p>
     * 解析会调外部模型、消耗 token，所以即使它「只是读取」也要留痕 ——
     * 事后要能回答「这个月的 AI 调用是谁点的」。
     */
    public static final String ACTION_RESUME_PARSE = "RESUME_PARSE";

    /** 职位 JD 的增删改 */
    public static final String ACTION_JOB_CREATE = "JOB_CREATE";
    public static final String ACTION_JOB_UPDATE = "JOB_UPDATE";
    public static final String ACTION_JOB_DELETE = "JOB_DELETE";

    /** 触发 JD 解析。理由同 {@link #ACTION_RESUME_PARSE} */
    public static final String ACTION_JOB_PARSE = "JOB_PARSE";

    /* ---------- 资源类型 ---------- */

    public static final String RESOURCE_USER = "USER";
    public static final String RESOURCE_ROLE = "ROLE";
    public static final String RESOURCE_PERMISSION = "PERMISSION";
    public static final String RESOURCE_AI_CONFIG = "AI_CONFIG";

    /** API 资源：用于记录「被拒绝的请求路径」，resourceId 存 URI */
    public static final String RESOURCE_API = "API";

    public static final String RESOURCE_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    public static final String RESOURCE_DOCUMENT = "DOCUMENT";
    public static final String RESOURCE_CONVERSATION = "CONVERSATION";
    public static final String RESOURCE_RESUME = "RESUME";
    public static final String RESOURCE_JOB = "JOB";
}
