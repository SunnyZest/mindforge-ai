package com.zest.mindforgeai.common.constant;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * 统一错误码，对齐 API 文档 §三十四
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
    CONFIRM_REQUIRED(40304, "高危操作需要二次确认"),
    CONFIRM_FAILED(40305, "二次确认验证失败，密码错误或凭证已过期"),

    USER_NOT_FOUND(40401, "用户不存在"),
    RESOURCE_NOT_FOUND(40402, "资源不存在"),
    KNOWLEDGE_BASE_NOT_FOUND(40403, "知识库不存在"),
    DOCUMENT_NOT_FOUND(40404, "文档不存在"),
    CHUNK_NOT_FOUND(40405, "文档分块不存在"),
    CONVERSATION_NOT_FOUND(40406, "会话不存在"),
    MESSAGE_NOT_FOUND(40407, "消息不存在"),
    JOB_NOT_FOUND(40408, "职位不存在"),
    RESUME_NOT_FOUND(40409, "简历不存在"),
    INTERVIEW_NOT_FOUND(40410, "面试不存在"),
    INTERVIEW_QUESTION_NOT_FOUND(40411, "面试题不存在"),
    INTERVIEW_REPORT_NOT_FOUND(40412, "面试报告尚未生成"),
    RESUME_JOB_ANALYSIS_NOT_FOUND(40413, "还没有做过这份简历与这个职位的匹配分析"),
    PROMPT_NOT_FOUND(40414, "提示词不存在"),
    ALERT_NOT_FOUND(40415, "告警不存在"),
    OFFER_NOT_FOUND(40416, "Offer 不存在"),
    JOB_EVENT_NOT_FOUND(40417, "日程不存在"),

    METHOD_NOT_ALLOWED(40501, "请求方法不支持"),

    USER_ALREADY_EXISTS(40901, "用户已存在"),
    STATUS_CONFLICT(40902, "资源状态冲突"),

    FILE_TOO_LARGE(41301, "文件过大"),
    FILE_TYPE_UNSUPPORTED(42201, "文件类型不支持"),
    TOO_MANY_REQUESTS(42901, "请求过于频繁，请稍后再试"),
    LOGIN_TOO_MANY_ATTEMPTS(42902, "登录失败次数过多，账号已临时锁定"),
    PASSWORD_CHANGE_TOO_MANY_ATTEMPTS(42903, "修改密码失败次数过多，账号已临时锁定"),

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

    /**
     * 按数值反查枚举。
     * <p>
     * 用途：AI 服务（Python）返回的错误码和本枚举是<b>同一套数值</b>
     * （见 Python 侧 {@code app/main.py} 的各 exception handler）。
     * 拿到它的 code 后翻回枚举，能保住原始语义 ——
     * 否则「这个 PDF 是扫描件、读不出文字」(42201) 会退化成一个笼统的 50201，
     * 前端只能显示「AI 服务异常」，而真正该说的是「这个文件处理不了」。
     * <p>
     * 顺带 HTTP 状态码也是对的：{@code GlobalExceptionHandler} 用 {@code code / 100} 当状态码，
     * 所以 42201 → 422、50201 → 502，和 Python 侧返回的状态一致。
     *
     * @return 没匹配到返回空（调用方自己决定兜底成哪个码）
     */
    public static Optional<ErrorCode> fromCode(int code) {
        return Arrays.stream(values()).filter(e -> e.code == code).findFirst();
    }
}
