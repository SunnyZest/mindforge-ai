package com.zest.mindforgeai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.WebConstants;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 已认证但无权限 → 403 + 统一响应体。
 * <p>
 * 注意：这里处理的是 <b>URL 规则</b>（{@code authorizeHttpRequests}）导致的拒绝，
 * 例如普通用户访问 {@code /admin/**}。方法级 {@code @PreAuthorize} 的拒绝走
 * {@code GlobalExceptionHandler}，两处都要记审计。

 *  本类依赖 AuditService，与 service 包形成循环依赖（service → SecurityUtils → LoginUser）。
 *   无 Bean 循环，可正常运行；若将来拆 Maven 多模块，需改为事件机制解耦。
 * @author crx
 * @since 2026-10-03
 */
@Slf4j
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper, AuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        log.warn("越权访问被拒绝（URL 规则）：{} {}", method, uri);
        auditService.recordFailure(AuditConstants.ACTION_ACCESS_DENIED,
                AuditConstants.RESOURCE_API, uri,
                Map.of("method", method, "reason", "URL 规则拒绝"));

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(WebConstants.JSON_CONTENT_TYPE);
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.FORBIDDEN)));
    }
}
