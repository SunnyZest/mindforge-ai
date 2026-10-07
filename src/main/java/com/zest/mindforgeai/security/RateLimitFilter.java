package com.zest.mindforgeai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.properties.SecurityProperties;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.utils.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 按 IP 限流 —— Redis 令牌桶。
 * <p>
 * 桶容量 = 每分钟配额，按时间匀速回填。比固定窗口平滑：
 * 固定窗口在窗口交界处会放过 2 倍突发。
 * <p>
 * <b>为什么用 Lua 而不是 Java 里读-算-写：</b>
 * 「读令牌数 → 按流逝时间回填 → 判断够不够 → 扣减 → 写回」是四步操作，
 * 在 Java 里分开做的话，两个并发请求会读到同一个旧值，各扣一次却都以为扣成功了，
 * 限流就漏了。Lua 在 Redis 里是单线程原子执行的，没有这个问题。
 * <p>
 * <b>为什么放在 Servlet Filter 层（而不是 Security 过滤器链里）：</b>
 * 要挡在认证之前。否则攻击者不带 token 也能把请求打到认证逻辑上，
 * 限流就挡不住这类消耗。
 * <p>
 * <b>刻意不加 {@code @Component}：</b>加了会被 Spring Boot 自动注册，
 * 且拿不到我们想要的 order（默认排到最后，跑到 Security 后面去了）。
 * 改由 {@code WebFilterConfig} 用 {@code FilterRegistrationBean} 显式注册。
 *
 * @author crx
 * @since 2026-10-07
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** 认证相关路径 —— 用更严的阈值 */
    private static final String[] AUTH_PATHS = {
            "/app/auth/login", "/app/auth/register", "/app/auth/refresh",
            "/admin/auth/login", "/admin/auth/refresh",
    };

    /** 豁免路径 —— 文档和静态资源，开发时会被频繁访问，限流只会碍事 */
    private static final String[] EXEMPT_PREFIXES = {
            "/doc.html", "/webjars/", "/v3/api-docs", "/swagger-ui", "/swagger-resources",
            "/favicon.ico", "/error",
    };

    /** 桶的 TTL（秒）：空闲这么久就回收，省内存 */
    private static final int BUCKET_TTL_SECONDS = 120;

    /**
     * 令牌桶 Lua 脚本。
     * <pre>
     *   KEYS[1] 桶的 key
     *   ARGV[1] 容量（= 每分钟配额）
     *   ARGV[2] 每秒回填多少令牌
     *   ARGV[3] 当前时间（秒，带小数）
     *   ARGV[4] key 的 TTL（秒）
     * 返回 1 = 放行，0 = 拒绝
     * </pre>
     */
    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            -- 首次访问：桶装满，避免冷启动就 429
            if tokens == nil then
                tokens = capacity
                ts = now
            end

            local delta = now - ts
            if delta > 0 then
                tokens = math.min(capacity, tokens + delta * refill)
                ts = now
            end

            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ts)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            return allowed
            """, Long.class);

    private final SecurityProperties properties;
    private final ClientIpResolver ipResolver;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public RateLimitFilter(SecurityProperties properties,
                           ClientIpResolver ipResolver,
                           ObjectMapper objectMapper,
                           StringRedisTemplate redis) {
        this.properties = properties;
        this.ipResolver = ipResolver;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.getRateLimit().isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // ⚠️ 用 getServletPath() 而不是 getRequestURI() ——
        // 后者包含 context-path（本项目是 /api），拿它做前缀匹配永远不命中
        String path = request.getServletPath();

        if (isExempt(path)) {
            chain.doFilter(request, response);
            return;
        }

        boolean authPath = isAuthPath(path);
        int limit = authPath
                ? properties.getRateLimit().getAuthPerMinute()
                : properties.getRateLimit().getDefaultPerMinute();

        String ip = ipResolver.resolve(request);
        String key = "mf:ratelimit:" + ip + (authPath ? ":auth" : ":default");

        if (!tryAcquire(key, limit)) {
            log.warn("限流触发：ip={} path={} limit={}/min", ip, path, limit);
            reject(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 取一个令牌。
     * <p>
     * <b>Redis 异常时放行（fail-open）</b>：Redis 挂了是运维问题，
     * 不该因此让整个服务不可用 —— 那等于把「限流组件故障」放大成「全站故障」。
     * 但要打 ERROR，因为这时候服务是裸奔的，得尽快修。
     */
    private boolean tryAcquire(String key, int perMinute) {
        double nowSeconds = System.currentTimeMillis() / 1000.0;
        double refillPerSecond = perMinute / 60.0;

        try {
            Long allowed = redis.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(key),
                    String.valueOf(perMinute),
                    String.valueOf(refillPerSecond),
                    String.valueOf(nowSeconds),
                    String.valueOf(BUCKET_TTL_SECONDS));
            return allowed == null || allowed == 1L;
        } catch (Exception e) {
            log.error("限流检查失败，本次请求放行（Redis 不可用时限流形同虚设，请尽快处理）", e);
            return true;
        }
    }

    private boolean isExempt(String path) {
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAuthPath(String path) {
        for (String auth : AUTH_PATHS) {
            if (path.equals(auth)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回 429 + 统一响应体。
     * <p>
     * 响应体格式和 {@code GlobalExceptionHandler} 保持一致（{@code {code, message, data}}），
     * 否则前端要为一类错误单独写解析。
     */
    private void reject(HttpServletResponse response) throws IOException {
        // HttpServletResponse 里没有 429 的常量（Servlet 规范没定义它），用 Spring 的
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 告诉客户端多久之后可以重试（秒）
        response.setHeader("Retry-After", "60");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.TOO_MANY_REQUESTS)));
    }
}
