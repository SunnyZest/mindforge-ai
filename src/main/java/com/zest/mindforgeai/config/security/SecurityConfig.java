package com.zest.mindforgeai.config.security;

import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.security.JwtAccessDeniedHandler;
import com.zest.mindforgeai.security.JwtAuthenticationEntryPoint;
import com.zest.mindforgeai.security.InternalTokenFilter;
import com.zest.mindforgeai.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
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
            // refresh：access token 已过期，靠 body 里的 refreshToken 认证
            // logout：靠 body 里的 refreshToken 认证，不依赖 access token
            "/app/auth/refresh",
            "/app/auth/logout",
            // 验证码与找回密码：都是「还没登录」的场景。
            // ⚠️ 正因为公开，发码接口的限流（60 秒冷却 + 每日上限）就是唯一的闸门，
            //    没有它就是一个免费的邮件轰炸入口。
            "/app/auth/verification-code",
            "/app/auth/reset-password",
            // ⚠️ 头像必须公开：它要能被 <img src> 直接加载，
            //    而 img 标签【带不上】Authorization 头。
            //    安全边界由接口自己兜（只允许 biz_type=avatars 的文件），
            //    不是靠鉴权 —— 所以那个接口里绝不能省掉业务类型校验。
            "/app/avatars/**",
            "/admin/auth/login",
            "/admin/auth/refresh",
            "/admin/auth/logout",
            // 接口文档
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    private final JwtAuthenticationFilter jwtFilter;
    private final InternalTokenFilter internalTokenFilter;
    private final JwtAuthenticationEntryPoint entryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          InternalTokenFilter internalTokenFilter,
                          JwtAuthenticationEntryPoint entryPoint,
                          JwtAccessDeniedHandler accessDeniedHandler) {
        this.jwtFilter = jwtFilter;
        this.internalTokenFilter = internalTokenFilter;
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
                        // ⚠️⚠️ 异步派发（ASYNC）必须放行 —— SSE / DeferredResult 这类异步请求
                        //   在响应写完之后，容器会【再走一遍过滤器链】（DispatcherType.ASYNC）。
                        //   而 SecurityContextHolderFilter 是 OncePerRequestFilter，
                        //   对异步派发默认跳过（shouldNotFilterAsyncDispatch = true），
                        //   所以这一趟里 SecurityContext 是空的 —— 但 AuthorizationFilter 照跑，
                        //   于是 anyRequest().authenticated() 会把【已经成功的请求】判成 Access Denied。
                        //
                        //   实测（2026-10-10）：流式对话内容正常推完、也落库了，
                        //   但收尾时报 AuthorizationDeniedException，客户端拿到的是
                        //   「peer closed connection without sending complete message body」——
                        //   看起来像「后端把连接掐了」，完全指不出真因是安全过滤器。
                        //
                        //   ⚠️ 放行它是安全的：DispatcherType 由容器按派发方式设置，客户端伪造不了；
                        //   而且能走到这一趟，说明最初的 REQUEST 派发已经完整鉴权过了
                        //   （JWT + @PreAuthorize 都在那一趟做的）。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 白名单：相当于 excludePathPatterns
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        // 内部接口：不走 JWT —— 调用方是 Python AI 服务，它没有用户身份，
                        // 也不该有（文档 §十七：Python 不参与用户认证）。
                        // 这里放行后由 InternalTokenFilter 校验 X-Service-Token。
                        // 生产环境还应叠加网络隔离（Docker 内网 / 只允许内网访问）。
                        .requestMatchers("/internal/**").permitAll()
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
                  .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                  // 内部接口的令牌校验要跑在 JWT 之前 —— 它的路径本来就不该有 JWT。
                  // ⚠️ 这一行必须在上面那行【之后】：Spring Security 要求
                  //    「参照物 filter」已经有注册顺序，而自定义 filter 只有被 addFilterBefore
                  //    加进链里之后才有顺序。顺序写反会报
                  //    "The Filter class JwtAuthenticationFilter does not have a registered order"。
                  .addFilterBefore(internalTokenFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
