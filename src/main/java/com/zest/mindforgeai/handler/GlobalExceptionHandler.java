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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
     * 统一构造错误响应。
     * <p>
     * ⚠️ <b>必须显式带上 {@code Content-Type: application/json}</b>。错误响应永远是 JSON，
     * 但内容协商看的是<b>请求的 {@code Accept} 头</b> —— 流式对话接口
     * （{@code /app/conversations/{id}/messages/stream}）的前端按 SSE 约定会发
     * {@code Accept: text/event-stream}，此时 Jackson 转换器「不可接受」，
     * 写错误体时抛 {@code HttpMediaTypeNotAcceptableException}：客户端拿到的是
     * <b>500</b> 而不是本该有的 404 / 400，日志里还多一条
     * 「Failure in @ExceptionHandler」ERROR。
     * <p>
     * 提前把响应头的内容类型钉死成 JSON，{@code AbstractMessageConverterMethodProcessor}
     * 就会走 {@code isContentTypePreset} 分支、<b>跳过内容协商</b>，正常写出 JSON。
     */
    private static ResponseEntity<Result<Void>> json(HttpStatus status, Result<Void> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * 业务异常。
     * <p>
     * 错误码前三位即 HTTP 状态码（40301 → 403、40901 → 409、40401 → 404），
     * 据此设置响应状态，与 Security 层的 401 / 403 保持一致；非法状态码兜底成 500。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode() / 100);
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return json(status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR,
                Result.fail(e.getCode(), e.getMessage()));
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
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突：{}", e.getMessage());
        return json(HttpStatus.CONFLICT, Result.fail(ErrorCode.STATUS_CONFLICT, "数据已存在，请检查是否有重复"));
    }

    /**
     * 账号密码错误 → 40101。
     * <p>
     * 这里只记「事件」，不记用户名 —— 用户名、IP、UA 等细节在 sys_audit_log 里，
     * 用响应里的 traceId 关联（审计记录的 request_id 就是它）。
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<Void>> handleBadCredentials(BadCredentialsException e) {
        log.warn("登录失败：用户名或密码错误");
        return json(HttpStatus.UNAUTHORIZED, Result.fail(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
    }

    /** 账号被禁用 → 40301 */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Result<Void>> handleDisabled(DisabledException e) {
        log.warn("登录失败：账号已被禁用");
        return json(HttpStatus.FORBIDDEN, Result.fail(ErrorCode.ACCOUNT_DISABLED));
    }

    /**
     * 方法级权限校验失败（{@code @PreAuthorize}）→ 40301。
     * <p>
     * 注意：URL 规则（{@code authorizeHttpRequests}）导致的 403 不会走到这里，
     * 由 {@code JwtAccessDeniedHandler} 处理；两处都记审计。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        log.warn("越权访问被拒绝（方法级 @PreAuthorize）：{} {}", method, uri);
        auditService.recordFailure(AuditConstants.ACTION_ACCESS_DENIED,
                AuditConstants.RESOURCE_API, uri,
                Map.of("method", method, "reason", "方法级权限不足"));

        return json(HttpStatus.FORBIDDEN, Result.fail(ErrorCode.FORBIDDEN));
    }

    /** 参数校验失败 → 40001 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() == null
                ? ErrorCode.PARAM_INVALID.getMessage()
                : e.getBindingResult().getFieldError().getDefaultMessage();
        return json(HttpStatus.BAD_REQUEST, Result.fail(ErrorCode.PARAM_INVALID, message));
    }

    /**
     * 路径不存在 → 404。
     * <p>
     * Spring Boot 3.2+ 访问未映射的路径会抛 {@code NoResourceFoundException}，
     * 若不单独处理会被下面的兜底抓成 500，让前端误判为服务端故障。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return json(HttpStatus.NOT_FOUND, Result.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 路径存在但请求方法不对 → 405。
     * <p>
     * 典型场景：只实现了 {@code DELETE /documents/{id}}，前端却发 {@code GET /documents/{id}} ——
     * 路径能匹配上，方法匹配不上，Spring 抛这个异常。
     * <b>不加这个 handler 会被兜底成 500</b>，前端会以为是服务端故障。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                 HttpServletRequest request) {
        log.warn("请求方法不支持：{} {}，该路径支持 {}",
                request.getMethod(), request.getRequestURI(), e.getSupportedHttpMethods());
        return json(HttpStatus.METHOD_NOT_ALLOWED, Result.fail(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 上传文件超过大小上限 → 413。
     * <p>
     * 上限配在 {@code spring.servlet.multipart.max-file-size}（当前 50MB）。
     * 不加这个 handler 会被兜底成 500 —— 而用户看到的应该是一个明确的「文件过大」。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小上限：{}", e.getMessage());
        return json(HttpStatus.PAYLOAD_TOO_LARGE, Result.fail(ErrorCode.FILE_TOO_LARGE));
    }

    /**
     * multipart 请求缺少必需的 part（比如上传接口没带 {@code file} 字段）→ 400。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return json(HttpStatus.BAD_REQUEST,
                Result.fail(ErrorCode.PARAM_INVALID, "缺少必要的上传字段：" + e.getRequestPartName()));
    }

    /** 请求体格式错误 / JSON 解析失败 → 40002 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return json(HttpStatus.BAD_REQUEST, Result.fail(ErrorCode.REQUEST_MALFORMED));
    }

    /** 路径变量 / 查询参数类型不匹配 → 40001 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return json(HttpStatus.BAD_REQUEST,
                Result.fail(ErrorCode.PARAM_INVALID, "参数 " + e.getName() + " 类型不正确"));
    }

    /** 缺少必需的查询参数 → 40001 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return json(HttpStatus.BAD_REQUEST,
                Result.fail(ErrorCode.PARAM_INVALID, "缺少必要参数：" + e.getParameterName()));
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

    /**
     * 兜底。
     * <p>
     * ⚠️ <b>响应一旦提交（committed），就绝不能再写响应体</b>。SSE / 流式接口开始推送后响应即已提交，
     * 此时客户端断开，容器会把底层的<b>裸 {@code IOException}</b>（"你的主机中的软件中止了一个已建立的连接"）
     * 直接抛到 MVC —— 它<b>不是</b> {@link #handleClientDisconnect} 认的
     * {@code AsyncRequestNotUsableException}（两者是兄弟，都继承 {@code IOException}），
     * 所以会落到这里。若仍按常规返回 {@code Result}，消息转换器找不到能写
     * {@code text/event-stream} 的转换器，会再抛一次 {@code HttpMessageNotWritableException} ——
     * 日志里就多一条唬人的 ERROR（其实业务早已正常完成、回答也已落库）。
     * <p>
     * 所以先看 {@code response.isCommitted()}：已提交就只记 DEBUG、返回 {@code null}
     * —— {@code HttpEntityMethodProcessor} 遇到 null 会直接跳过、不写 body。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e, HttpServletRequest request,
                                                    HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("响应已提交，跳过异常响应体（客户端多半已断开）：{} {}",
                    request.getMethod(), request.getServletPath(), e);
            return null;
        }
        log.error("未处理异常", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, Result.fail(ErrorCode.SYSTEM_ERROR));
    }
}
