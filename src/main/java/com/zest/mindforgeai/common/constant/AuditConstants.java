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

    /**
     * 上传头像。
     * <p>
     * 和改资料分开记：头像会产生一个【公开可读】的对象（见 /app/avatars/{fileId}），
     * 出事时要能查出「谁在什么时候传了什么」，单独一个 action 才好过滤。
     */
    public static final String ACTION_USER_UPLOAD_AVATAR = "USER_UPLOAD_AVATAR";

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

    /** 简历：上传（创建）与删除 */
    public static final String ACTION_RESUME_CREATE = "RESUME_CREATE";
    public static final String ACTION_RESUME_DELETE = "RESUME_DELETE";

    /**
     * 触发简历解析。
     * <p>
     * 解析会调外部模型、消耗 token，所以即使它「只是读取」也要留痕 ——
     * 事后要能回答「这个月的 AI 调用是谁点的」。
     */
    public static final String ACTION_RESUME_PARSE = "RESUME_PARSE";

    /**
     * 触发简历 AI 优化。
     * <p>
     * 理由同 {@link #ACTION_RESUME_PARSE} —— 消耗 token 的调用都要留痕。
     * 注意它<b>不写任何业务数据</b>（只回建议），留痕的目的是回答
     * 「这个月的 AI 调用是谁点的」，不是「谁改了数据」。
     */
    public static final String ACTION_RESUME_OPTIMIZE = "RESUME_OPTIMIZE";

    /** 职位 JD 的增删改 */
    public static final String ACTION_JOB_CREATE = "JOB_CREATE";
    public static final String ACTION_JOB_UPDATE = "JOB_UPDATE";
    public static final String ACTION_JOB_DELETE = "JOB_DELETE";

    /** 触发 JD 解析。理由同 {@link #ACTION_RESUME_PARSE} */
    public static final String ACTION_JOB_PARSE = "JOB_PARSE";

    /** AI 面试：创建与删除 */
    public static final String ACTION_INTERVIEW_CREATE = "INTERVIEW_CREATE";
    public static final String ACTION_INTERVIEW_DELETE = "INTERVIEW_DELETE";

    /**
     * 给面试出题。
     * <p>
     * ⚠️ 和「创建面试」分开记：创建只是一行数据，出题是一次真实的模型调用。
     * 合成一个 action 的话，「这个月面试功能烧了多少 token」就查不出来了。
     */
    public static final String ACTION_INTERVIEW_GENERATE = "INTERVIEW_GENERATE";

    /** 提交单题回答（含 AI 评分） */
    public static final String ACTION_INTERVIEW_ANSWER = "INTERVIEW_ANSWER";

    /** 结束面试并生成复盘报告 */
    public static final String ACTION_INTERVIEW_FINISH = "INTERVIEW_FINISH";

    /**
     * 触发简历-JD 匹配分析。
     * <p>
     * 理由同 {@link #ACTION_RESUME_PARSE} —— 消耗 token 的调用都要留痕。
     * 它<b>会写业务数据</b>（落 {@code resume_job_analysis}），但重跑是覆盖，
     * 所以留痕主要是为了回答「这个月的 AI 调用是谁点的」。
     */
    public static final String ACTION_RESUME_MATCH = "RESUME_MATCH";

    /**
     * 提示词变更。
     * <p>
     * ⚠️ 改提示词会【立刻影响所有用户的 AI 输出】，是典型的高危操作，
     * 所以创建 / 修改 / 发布 / 回滚 / 删除都要留痕 —— 出了问题要能查出
     * 「是哪次改动、谁改的、从哪一版换到了哪一版」。
     */
    public static final String ACTION_PROMPT_CREATE = "PROMPT_CREATE";
    public static final String ACTION_PROMPT_UPDATE = "PROMPT_UPDATE";
    public static final String ACTION_PROMPT_PUBLISH = "PROMPT_PUBLISH";
    public static final String ACTION_PROMPT_ROLLBACK = "PROMPT_ROLLBACK";
    public static final String ACTION_PROMPT_DELETE = "PROMPT_DELETE";

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
    public static final String RESOURCE_INTERVIEW = "INTERVIEW";
    public static final String RESOURCE_RESUME_JOB_ANALYSIS = "RESUME_JOB_ANALYSIS";
    public static final String RESOURCE_PROMPT = "PROMPT";
}
