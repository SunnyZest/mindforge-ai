package com.zest.mindforgeai.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希工具（无状态）
 *
 * @author crx
 * @since 2026-10-05
 */
public final class HashUtils {

    private HashUtils() {
    }

    /**
     * 计算 SHA-256，返回 64 位小写十六进制字符串。
     * <p>
     * 用途：{@code sys_file.content_hash} —— 上传前先算哈希查重，命中就复用已有对象（秒传），
     * 避免同一份文件重复占用对象存储空间。
     */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走不到这里
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    /** 计算字符串的 SHA-256（refresh token 的哈希用的就是它） */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
