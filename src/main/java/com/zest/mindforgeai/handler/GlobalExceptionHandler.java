package com.zest.mindforgeai.handler;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一转成 Result 返回前端
 *
 * @author crx
 * @since 2026-10-03
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常。
     * <p>
     * 错误码前三位即 HTTP 状态码（40301 → 403、40901 → 409、40401 → 404），
     * 据此设置响应状态，与 Security 层的 401 / 403 保持一致。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletResponse response) {
        int status = e.getCode() / 100;
        // 兜底：非法 HTTP 状态码按 500 处理
        response.setStatus(status >= 100 && status <= 599 ? status : 500);
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 账号密码错误 */
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleBadCredentials(BadCredentialsException e) {
        return Result.fail(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }

    /** 账号被禁用 */
    @ExceptionHandler(DisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleDisabled(DisabledException e) {
        return Result.fail(ErrorCode.FORBIDDEN, "账号已被禁用");
    }

    /** 方法级权限校验失败（@PreAuthorize） */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.fail(ErrorCode.FORBIDDEN);
    }

    /** 参数校验失败 → 40001 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() == null
                ? ErrorCode.PARAM_INVALID.getMessage()
                : e.getBindingResult().getFieldError().getDefaultMessage();
        return Result.fail(ErrorCode.PARAM_INVALID, message);
    }

    /**
     * 路径不存在 → 404。
     * Spring Boot 3.2+ 访问未映射的路径会抛 NoResourceFoundException，
     * 若不单独处理会被下面的兜底抓成 500，让前端误判为服务端故障。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.fail(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 请求体格式错误 / JSON 解析失败 → 40002 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return Result.fail(ErrorCode.REQUEST_MALFORMED);
    }

    /** 路径变量 / 查询参数类型不匹配 → 40001 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(ErrorCode.PARAM_INVALID, "参数 " + e.getName() + " 类型不正确");
    }

    /** 兜底 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
