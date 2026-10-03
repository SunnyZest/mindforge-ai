package com.zest.mindforgeai.utils;

import com.zest.mindforgeai.common.constant.JwtConstants;
import com.zest.mindforgeai.common.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具：签发 / 解析 Access Token
 *
 * @author crx
 * @since 2026-10-03
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long accessExpireMs;

    public JwtUtil(JwtProperties jwtProperties) {
        // 配置里存的是 Base64 字符串，必须 Base64 解码，不能用 getBytes()
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
        this.accessExpireMs = jwtProperties.getAccessTokenExpireMinutes() * 60_000L;
    }

    /**
     * 签发 Access Token。
     * 文档 §5.2：sub 放 userId，username / roles 作为独立 claim
     */
    public String generateAccessToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(JwtConstants.CLAIM_USERNAME, username)
                .claim(JwtConstants.CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpireMs))
                .signWith(key)
                .compact();
    }

    /** Access Token 有效期（秒），对应文档的 expiresIn */
    public long getAccessExpireSeconds() {
        return accessExpireMs / 1000;
    }

    /** 解析并校验签名，失败抛异常 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }

    /** 取 userId（sub） */
    public Long extractUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    /** 取用户名 */
    public String extractUsername(String token) {
        return parse(token).get(JwtConstants.CLAIM_USERNAME, String.class);
    }

    /** 取角色 code 列表 */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return parse(token).get(JwtConstants.CLAIM_ROLES, List.class);
    }

    /**
     * 校验 token：签名、有效期，并要求 sub 是合法的 userId。
     * 任一不满足返回 false（过期 / 篡改 / 格式错 / sub 非数字都会抛异常）
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = parse(token);
            Long.valueOf(claims.getSubject());   // sub 必须是数字 userId
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
