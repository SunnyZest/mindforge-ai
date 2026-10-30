package com.zest.mindforgeai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.SessionConstants;
import com.zest.mindforgeai.common.constant.WebConstants;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   CustomUserDetailsService customUserDetailsService,
                                   ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.customUserDetailsService = customUserDetailsService;
        this.objectMapper = objectMapper;
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

        // 顺手把会话 id 挂到 request 上：「我的登录设备」接口要拿它标出「本机」。
        // 老 token（加 sid claim 之前签发的）这里是 null，读的地方必须能处理。
        request.setAttribute(SessionConstants.SESSION_ID_ATTR, jwtUtil.extractSessionId(token));

        if (userId != null
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            LoginUser loginUser;
            try {
                loginUser = customUserDetailsService.loadUserById(userId);
            } catch (UsernameNotFoundException e) {
                // ★ 用户已经不存在（账号被删）。token 本身还没过期，但它指向的人没了。
                //
                //   按「无效 token」处理：不写 SecurityContext，直接放行，
                //   由后面的授权层返回 401 —— 和上面 token 无效的分支走同一条路。
                //
                //   ⚠️ 必须在这里接住异常：JwtAuthenticationFilter 排在
                //   ExceptionTranslationFilter【之前】，从它里面抛出去的异常没人接，
                //   会一路冒到容器变成 500「系统内部异常」——
                //   用户看到的是「服务器挂了」，而真实原因是「账号已删除」。
                log.warn("token 指向的用户已不存在，按无效 token 处理：userId={} uri={}",
                        userId, request.getRequestURI());
                chain.doFilter(request, response);
                return;
            }

            // ★ 账号已被禁用 → 直接拒绝，不写入 SecurityContext。
            //
            //   为什么必须在这里拦：isEnabled() 的检查原本只发生在登录时
            //   （DaoAuthenticationProvider 内部），JWT 请求不走那条路径。
            //   不拦的话，管理员禁用某用户后，他手里的 token 在剩余有效期内
            //   （当前配置最长 30 分钟）仍然能正常访问。
            //
            //   为什么用 403 而不是 401：前端拦截器对 401 的反应是「用 refreshToken
            //   换新 token 再重试」，而新 token 同样过不了这道检查 —— 会变成死循环。
            //   403 + 独立错误码 40303 能让前端明确提示「账号已被禁用」并停止重试。
            if (!loginUser.isEnabled()) {
                log.warn("已禁用账号尝试访问：userId={} username={} uri={}",
                        loginUser.getUserId(), loginUser.getUsername(), request.getRequestURI());
                writeAccountDisabled(response);
                return;
            }

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

    /** 账号被禁用时的统一响应 */
    private void writeAccountDisabled(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(WebConstants.JSON_CONTENT_TYPE);
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.ACCOUNT_DISABLED)));
    }
}
