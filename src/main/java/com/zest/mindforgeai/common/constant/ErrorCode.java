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
    RESOURCE_FORBIDDEN(40302, "无权访问该资源"),

    USER_NOT_FOUND(40401, "用户不存在"),
    RESOURCE_NOT_FOUND(40402, "资源不存在"),
    KNOWLEDGE_BASE_NOT_FOUND(40403, "知识库不存在"),
    DOCUMENT_NOT_FOUND(40404, "文档不存在"),

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
