package com.zest.mindforgeai.common.exception;

import com.zest.mindforgeai.common.constant.ErrorCode;
import lombok.Getter;

/**
 * 业务异常，由 GlobalExceptionHandler 统一转成 Result 返回前端
 *
 * @author crx
 * @since 2026-10-03
 */
@Getter
public class BizException extends RuntimeException {

    /** 错误码 */
    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}
