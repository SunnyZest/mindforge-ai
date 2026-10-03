package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置，对应 application.yml 的 mindforge.jwt.*
 *
 * @author crx
 * @since 2026-10-03
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.jwt")
public class JwtProperties {

    /** Base64 编码的 HS256 密钥 */
    private String secret;

    /** Access Token 有效期（分钟） */
    private long accessTokenExpireMinutes;

    /** Refresh Token 有效期（天） */
    private long refreshTokenExpireDays;
}
