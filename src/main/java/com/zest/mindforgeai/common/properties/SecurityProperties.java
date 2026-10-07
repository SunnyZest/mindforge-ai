package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流与登录保护的配置，对应 {@code mindforge.security.*}。
 *
 * @author crx
 * @since 2026-10-07
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.security")
public class SecurityProperties {

    private RateLimit rateLimit = new RateLimit();
    private LoginAttempt loginAttempt = new LoginAttempt();

    /**
     * 是否信任 {@code X-Forwarded-For} 头。
     * <p>
     * ⚠️ <b>默认 false，不要随便打开。</b>
     * 这个头客户端可以随便伪造 —— 如果后端前面没有可信代理却去读它，
     * 攻击者每次请求换个假 IP 就能绕过限流和登录失败计数，等于没做。
     * <p>
     * 只有当前面确实挂了 Nginx / 网关（且它会把真实 IP 写进这个头）时才设为 true。
     */
    private boolean trustForwardedHeaders = false;

    @Data
    public static class RateLimit {
        /** 是否开启限流 */
        private boolean enabled = true;
        /** 普通接口：每分钟每 IP 允许的请求数 */
        private int defaultPerMinute = 100;
        /** 认证相关接口（登录/注册/刷新）：每分钟每 IP 允许的请求数，要比普通接口严 */
        private int authPerMinute = 10;
    }

    @Data
    public static class LoginAttempt {
        /** 是否开启登录失败限制 */
        private boolean enabled = true;
        /** 同一账号连续失败多少次后锁定 */
        private int maxPerUsername = 5;
        /** 同一 IP 连续失败多少次后锁定（放宽些，避免 NAT 下误伤） */
        private int maxPerIp = 20;
        /** 锁定时长（分钟） */
        private int lockMinutes = 15;
    }
}
