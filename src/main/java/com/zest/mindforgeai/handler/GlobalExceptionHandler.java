package com.zest.mindforgeai.handler;

import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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

import java.util.Map;

/**
 * 全局异常处理：统一转成 Result 返回前端
 *
 * @author crx
 * @since 2026-10-03
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final AuditService auditService;

    public GlobalExceptionHandler(AuditService auditService) {
        this.auditService = auditService;
    }

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

    /**
     * 唯一约束冲突 → 40902。
     * <p>
     * <b>为什么需要这层兜底</b>：业务代码里的「唯一性检查」通常查的是「未逻辑删除」的记录
     * （MyBatis-Plus 会自动加 {@code deleted = 0}），但数据库的 UNIQUE 索引<b>不管逻辑删除</b>。
     * 所以「先逻辑删掉、再用同名重建」这种场景会绕过业务检查，直接撞到索引上。
     * <p>
     * 另外，业务检查在并发下必然有竞态窗口，只有数据库约束是最终防线。
     * 各 Service 保留业务级检查是为了给出更友好的提示，这里是兜底。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突：{}", e.getMessage());
        return Result.fail(ErrorCode.STATUS_CONFLICT, "数据已存在，请检查是否有重复");
    }

    /**
     * 账号密码错误 → 40101。
     * <p>
     * 这里只记「事件」，不记用户名 —— 用户名、IP、UA 等细节在 sys_audit_log 里，
     * 用响应里的 traceId 关联（审计记录的 request_id 就是它）。
     */
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleBadCredentials(BadCredentialsException e) {
        log.warn("登录失败：用户名或密码错误");
        return Result.fail(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }

    /** 账号被禁用 → 40301 */
    @ExceptionHandler(DisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleDisabled(DisabledException e) {
        log.warn("登录失败：账号已被禁用");
        return Result.fail(ErrorCode.ACCOUNT_DISABLED);
    }

    /**
     * 方法级权限校验失败（{@code @PreAuthorize}）→ 40301。
     * <p>
     * 注意：URL 规则（{@code authorizeHttpRequests}）导致的 403 不会走到这里，
     * 由 {@code JwtAccessDeniedHandler} 处理；两处都记审计。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        log.warn("越权访问被拒绝（方法级 @PreAuthorize）：{} {}", method, uri);
        auditService.recordFailure(AuditConstants.ACTION_ACCESS_DENIED,
                AuditConstants.RESOURCE_API, uri,
                Map.of("method", method, "reason", "方法级权限不足"));

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
