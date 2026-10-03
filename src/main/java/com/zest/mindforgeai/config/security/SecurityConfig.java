package com.zest.mindforgeai.config.security;

import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.security.JwtAccessDeniedHandler;
import com.zest.mindforgeai.security.JwtAuthenticationEntryPoint;
import com.zest.mindforgeai.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置：无状态 JWT + RBAC
 *
 * @author crx
 * @since 2026-10-03
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // 启用 @PreAuthorize
public class SecurityConfig {

    /**
     * 无需认证即可访问的白名单。
     * 注意：这里的路径不含 context-path（/api），Security 匹配的是 context-path 之后的路径。
     */
    private static final String[] PUBLIC_URLS = {
            "/app/auth/login",
            "/app/auth/register",
            "/admin/auth/login",
            // 接口文档
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtAuthenticationEntryPoint entryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          JwtAuthenticationEntryPoint entryPoint,
                          JwtAccessDeniedHandler accessDeniedHandler) {
        this.jwtFilter = jwtFilter;
        this.entryPoint = entryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 无状态：禁 session + 禁 CSRF（JWT 不依赖 Cookie）
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 白名单：相当于 excludePathPatterns
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        // 管理端：需要 ADMIN / SUPER_ADMIN（文档 §7.2、§33.4）
                        .requestMatchers("/admin/**")
                        .hasAnyRole(RoleConstants.ADMIN, RoleConstants.SUPER_ADMIN)
                        // 其余（含 /app/**）必须登录
                        .anyRequest().authenticated()
                )
                // 关掉 Security 默认的表单/basic 登录，全部交给 JWT
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // 认证失败 401 / 授权失败 403 都返回统一 JSON
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // 把 JWT 过滤器插在用户名密码过滤器之前
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
