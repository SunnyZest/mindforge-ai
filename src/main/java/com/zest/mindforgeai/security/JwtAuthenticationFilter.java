package com.zest.mindforgeai.security;

import com.zest.mindforgeai.common.constant.WebConstants;
import com.zest.mindforgeai.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：解析 Authorization 头，把 {@link LoginUser} 写入 SecurityContext
 *
 * @author crx
 * @since 2026-10-03
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   CustomUserDetailsService customUserDetailsService) {
        this.jwtUtil = jwtUtil;
        this.customUserDetailsService = customUserDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(WebConstants.HEADER_AUTHORIZATION);

        // 1. 没带 token → 不设置认证信息，直接放行
        //    后面的授权层会决定是 401 还是白名单放行
        if (header == null || !header.startsWith(WebConstants.BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(WebConstants.BEARER_PREFIX.length());

        // 2. token 无效（过期 / 篡改 / sub 非数字）→ 同样放行，交给授权层返回 401
        if (!jwtUtil.isTokenValid(token)) {
            chain.doFilter(request, response);
            return;
        }

        // 3. 用 userId 加载用户：userId 永不变，token 不受用户名变更影响
        Long userId = jwtUtil.extractUserId(token);

        if (userId != null
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            LoginUser loginUser = customUserDetailsService.loadUserById(userId);

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                            loginUser,                        // principal：携带 userId + username + roles
                            null,                             // credentials，token 场景置空
                            loginUser.getAuthorities());

            authToken.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        // 4. 放行
        chain.doFilter(request, response);
    }
}
