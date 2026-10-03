package com.zest.mindforgeai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.WebConstants;
import com.zest.mindforgeai.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 已认证但无权限 → 403 + 统一响应体
 *
 * @author crx
 * @since 2026-10-03
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(WebConstants.JSON_CONTENT_TYPE);
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.FORBIDDEN)));
    }
}
