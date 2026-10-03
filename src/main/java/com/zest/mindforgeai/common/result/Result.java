package com.zest.mindforgeai.common.result;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.context.TraceIdHolder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统一返回给前端的响应体，文档 §8.4
 *
 * <pre>
 * 成功：{ "code": 0,     "message": "success",   "data": {}, "traceId": "7f82a1" }
 * 失败：{ "code": 40001, "message": "参数校验失败", "data": null, "traceId": "7f82a1" }
 * </pre>
 *
 * @author crx
 * @since 2026-10-03
 */
@Data
@Schema(description = "统一响应体")
public class Result<T> {

    @Schema(description = "0 表示成功，非 0 见错误码表")
    private int code;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "业务数据，失败时为 null")
    private T data;

    @Schema(description = "链路追踪 id")
    private String traceId;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceIdHolder.get();
    }

    /** 成功，带数据 */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 成功，无数据 */
    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /** 失败，指定错误码与提示信息 */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败，使用错误码默认提示信息 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败，自定义提示信息覆盖错误码默认值 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }
}
