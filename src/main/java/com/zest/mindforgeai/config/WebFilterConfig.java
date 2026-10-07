package com.zest.mindforgeai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.properties.SecurityProperties;
import com.zest.mindforgeai.security.RateLimitFilter;
import com.zest.mindforgeai.utils.ClientIpResolver;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 注册 Servlet 层的过滤器。
 *
 * @author crx
 * @since 2026-10-07
 */
@Configuration
public class WebFilterConfig {

    /**
     * 限流过滤器。
     * <p>
     * ⚠️ <b>order 必须比 Spring Security 的过滤器链小</b>
     * （{@code SecurityProperties.DEFAULT_FILTER_ORDER} 是 -100）。
     * 否则限流会跑在认证之后 —— 攻击者不带 token 也能把请求打到认证逻辑上，
     * 限流就挡不住这类消耗。
     *
     * @param properties   限流配置
     * @param ipResolver   取客户端 IP（内部处理了 X-Forwarded-For 的可信问题）
     * @param objectMapper 用来把 429 的响应体序列化成和 GlobalExceptionHandler 一致的格式
     * @param redis        令牌桶计数存在 Redis 里（跨实例共享）
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            SecurityProperties properties,
            ClientIpResolver ipResolver,
            ObjectMapper objectMapper,
            StringRedisTemplate redis) {

        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new RateLimitFilter(properties, ipResolver, objectMapper, redis));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("rateLimitFilter");
        return registration;
    }
}
