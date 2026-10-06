package com.zest.mindforgeai.security;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.properties.InternalApiProperties;
import com.zest.mindforgeai.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * {@code /internal/**} 的认证过滤器。
 * <p>
 * <b>为什么不用 JWT</b>：这些接口是给 Python AI 服务调的，它没有用户身份，
 * 也不该有 —— 按文档 §十七 的原则，Python 不参与用户认证。
 * 所以这里用服务间共享密钥（{@code X-Service-Token}）。
 * <p>
 * <b>fail-safe</b>：如果 {@code mindforge.internal.service-token} 没配置，
 * 直接拒绝所有请求而不是放行 —— 配置漏了应该「用不了」，而不是「裸奔」。
 *
 * @author crx
 * @since 2026-10-06
 */
@Slf4j
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Service-Token";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final InternalApiProperties properties;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(InternalApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // ⚠️ 必须用 getServletPath() 而不是 getRequestURI()！
        //    getRequestURI() 包含 context-path，本项目 context-path=/api，
        //    所以拿到的会是 /api/internal/documents/claim，
        //    用 startsWith("/internal/") 判断会永远为 false → 过滤器形同虚设、接口裸奔。
        //    getServletPath() 返回的是 context-path 之后的部分（/internal/documents/claim）。
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            // 极少数容器下 servletPath 可能为空，退回用 requestURI 去掉 contextPath
            String uri = request.getRequestURI();
            String ctx = request.getContextPath();
            path = (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx))
                    ? uri.substring(ctx.length())
                    : uri;
        }

        // 只管 /internal/**，其他路径原样放行
        if (!path.startsWith(INTERNAL_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String expected = properties.getServiceToken();
        if (!StringUtils.hasText(expected)) {
            log.error("收到 /internal 请求但 mindforge.internal.service-token 未配置，已拒绝：{} {}",
                    request.getMethod(), request.getRequestURI());
            reject(response, "内部接口未配置服务令牌");
            return;
        }

        String actual = request.getHeader(HEADER);
        if (!expected.equals(actual)) {
            log.warn("内部接口令牌校验失败：{} {} from={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            reject(response, "服务令牌无效");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.UNAUTHORIZED, message)));
    }
}
