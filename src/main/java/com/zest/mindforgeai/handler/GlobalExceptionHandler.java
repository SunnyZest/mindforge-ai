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
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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
     * <p>
     * Spring Boot 3.2+ 访问未映射的路径会抛 {@code NoResourceFoundException}，
     * 若不单独处理会被下面的兜底抓成 500，让前端误判为服务端故障。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.fail(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * 路径存在但请求方法不对 → 405。
     * <p>
     * 典型场景：只实现了 {@code DELETE /documents/{id}}，前端却发 {@code GET /documents/{id}} ——
     * 路径能匹配上，方法匹配不上，Spring 抛这个异常。
     * <b>不加这个 handler 会被兜底成 500</b>，前端会以为是服务端故障。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                 HttpServletRequest request) {
        log.warn("请求方法不支持：{} {}，该路径支持 {}",
                request.getMethod(), request.getRequestURI(), e.getSupportedHttpMethods());
        return Result.fail(ErrorCode.METHOD_NOT_ALLOWED);
    }

    /**
     * 上传文件超过大小上限 → 413。
     * <p>
     * 上限配在 {@code spring.servlet.multipart.max-file-size}（当前 50MB）。
     * 不加这个 handler 会被兜底成 500 —— 而用户看到的应该是一个明确的「文件过大」。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小上限：{}", e.getMessage());
        return Result.fail(ErrorCode.FILE_TOO_LARGE);
    }

    /**
     * multipart 请求缺少必需的 part（比如上传接口没带 {@code file} 字段）→ 400。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingPart(MissingServletRequestPartException e) {
        return Result.fail(ErrorCode.PARAM_INVALID, "缺少必要的上传字段：" + e.getRequestPartName());
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

    /** 缺少必需的查询参数 → 40001 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ErrorCode.PARAM_INVALID, "缺少必要参数：" + e.getParameterName());
    }

    /**
     * 客户端在响应写完之前断开了连接 —— <b>这不是服务端的错误</b>，只记 DEBUG。
     * <p>
     * 典型场景：调用方等不及先放弃了（curl 的 {@code --max-time} 到了、前端 axios 超时、
     * 用户直接关掉页面）。此时服务端其实还在正常处理、甚至已经算完了，
     * 只是往一个已经死掉的连接写响应，{@code ServletOutputStream} 抛 IOException，
     * Spring 把它包成这个异常。
     * <p>
     * ⚠️ <b>刻意返回 {@code void}</b>：连接已经没了，再往响应里写 JSON 只会再抛一次。
     * <p>
     * ⚠️ 不加这个 handler 的话，它会被下面的兜底抓成「未处理异常」+ 完整堆栈打 ERROR。
     * 实测（2026-10-10）：调 AI 优化接口时 curl 提前 20 秒放弃，Java 在两分钟后算完、
     * 往死连接写 —— 于是日志里多了一条看着像故障的「未处理异常」。
     * 这类噪音一天能刷出几十条，真正的问题反而被淹没。
     * <p>
     * 顺带说明：这正好是「<b>客户端超时必须大于服务端耗时</b>」那条坑的现场证据 ——
     * 出现这个异常，通常说明调用方的超时配短了。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e,
                                       HttpServletRequest request) {
        log.debug("客户端提前断开了连接（响应未写完）：{} {}",
                request.getMethod(), request.getServletPath());
    }

    /** 兜底 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
