package com.zest.mindforgeai.common.constant;

import lombok.Getter;

/**
 * 统一错误码，对齐《MindForge-AI-API开发文档-双前端单后端-RBAC》§三十四
 *
 * @author crx
 * @since 2026-10-03
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    PARAM_INVALID(40001, "参数校验失败"),
    REQUEST_MALFORMED(40002, "请求格式错误"),

    UNAUTHORIZED(40101, "未登录"),
    TOKEN_EXPIRED(40102, "Token 已过期"),
    TOKEN_INVALID(40103, "Token 无效"),

    FORBIDDEN(40301, "无权限"),

    /**
     * 「资源不是你的」这个场景【不用】它 —— 那种情况一律返回「不存在」(404)。
     * <p>
     * 因为资源 id 是连续自增的，返回 403 等于告诉调用方
     * 「这个 id 存在，只是不归你」，相当于帮他确认了别人的数据范围。
     * <p>
     * 这个码留给「资源确实是你的，但你没有操作它的权限」这类场景
     * （比如知识库的只读成员试图删除它）。
     */
    RESOURCE_FORBIDDEN(40302, "无权访问该资源"),
    ACCOUNT_DISABLED(40303, "账号已被禁用"),

    USER_NOT_FOUND(40401, "用户不存在"),
    RESOURCE_NOT_FOUND(40402, "资源不存在"),
    KNOWLEDGE_BASE_NOT_FOUND(40403, "知识库不存在"),
    DOCUMENT_NOT_FOUND(40404, "文档不存在"),
    CHUNK_NOT_FOUND(40405, "文档分块不存在"),

    METHOD_NOT_ALLOWED(40501, "请求方法不支持"),

    USER_ALREADY_EXISTS(40901, "用户已存在"),
    STATUS_CONFLICT(40902, "资源状态冲突"),

    FILE_TOO_LARGE(41301, "文件过大"),
    FILE_TYPE_UNSUPPORTED(42201, "文件类型不支持"),
    TOO_MANY_REQUESTS(42901, "请求过于频繁"),

    SYSTEM_ERROR(50001, "系统内部异常"),
    AI_SERVICE_ERROR(50201, "AI 服务异常"),
    LLM_CALL_FAILED(50202, "LLM 调用失败"),
    VECTOR_DB_ERROR(50203, "向量数据库异常"),
    AI_SERVICE_TIMEOUT(50401, "AI 服务超时");

    /** 错误码 */
    private final int code;

    /** 默认提示信息 */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
