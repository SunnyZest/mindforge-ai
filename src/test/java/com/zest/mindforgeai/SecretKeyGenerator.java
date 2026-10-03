package com.zest.mindforgeai;

import io.jsonwebtoken.Jwts;

import java.util.Base64;

/**
 * 一次性工具：生成 HS256 密钥（Base64）。
 * <p>
 * 把输出复制到 application-dev.yml 的 {@code mindforge.jwt.secret}。
 * <p>
 * 注意：jjwt 0.12.x 中 {@code SignatureAlgorithm} / {@code Keys.secretKeyFor} 已废弃，
 * 改用 {@code Jwts.SIG.HS256.key().build()}。
 *
 * @author crx
 * @since 2026-10-03
 */
public class SecretKeyGenerator {

    public static void main(String[] args) {
        byte[] keyBytes = Jwts.SIG.HS256.key().build().getEncoded();
        System.out.println(Base64.getEncoder().encodeToString(keyBytes));
    }
}
