package com.zest.mindforgeai.utils;

import com.zest.mindforgeai.common.properties.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 取客户端真实 IP，供限流和登录失败计数使用。
 * <p>
 * ⚠️ <b>这里有个容易被忽视的安全陷阱。</b>
 * {@code X-Forwarded-For} 是<b>客户端可以随便伪造</b>的请求头。
 * 如果后端前面没有可信代理却无条件读它，攻击者每次请求换一个假 IP，
 * 就能绕过限流和登录失败计数 —— 防护形同虚设。
 * <p>
 * 所以这里由 {@code mindforge.security.trust-forwarded-headers} 控制，
 * <b>默认不信任</b>，直接用 TCP 连接的来源地址。
 * 只有前面确实挂了 Nginx / 网关（并且它会覆盖写入这个头）时才打开。
 *
 * @author crx
 * @since 2026-10-07
 */
@Component
public class ClientIpResolver {

    private static final String HEADER_XFF = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";

    private final SecurityProperties properties;

    public ClientIpResolver(SecurityProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustForwardedHeaders()) {
            String forwarded = extractFromXff(request.getHeader(HEADER_XFF));
            if (forwarded != null) {
                return forwarded;
            }
            String realIp = request.getHeader(HEADER_REAL_IP);
            if (StringUtils.hasText(realIp)) {
                return realIp.trim();
            }
        }
        // 不信任代理头时，用 TCP 层拿到的地址 —— 这个伪造不了
        return request.getRemoteAddr();
    }

    /**
     * 从 X-Forwarded-For 里取最靠近客户端的那一个。
     * <p>
     * 格式是 {@code 客户端IP, 代理1IP, 代理2IP} —— 第一个才是真实客户端。
     * 注意后面的值可能是代理加的，第一个值如果是客户端伪造的，
     * 那就说明前面的代理没做覆盖写入，此时这个头本来就不可信。
     */
    private String extractFromXff(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        int comma = header.indexOf(',');
        String first = (comma > 0 ? header.substring(0, comma) : header).trim();
        return StringUtils.hasText(first) ? first : null;
    }
}
