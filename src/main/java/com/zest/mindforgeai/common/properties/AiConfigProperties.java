package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模型配置的加密设置，对应 {@code mindforge.ai-config.*}。
 * <p>
 * 管理端维护的 AI 配置里要存第三方 API Key —— 那是能直接花钱的凭据，
 * <b>绝不能明文落库</b>，所以用 AES-GCM 加密后再存。
 * 密钥本身放配置（开发环境在 {@code application-dev.yml}，生产用环境变量注入），
 * 不进版本库。
 *
 * @author crx
 * @since 2026-10-08
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.ai-config")
public class AiConfigProperties {

    /**
     * API Key 的加密密钥，Base64 编码。
     * <p>
     * 解码后必须是 16 / 24 / 32 字节（对应 AES-128 / AES-192 / AES-256）。
     * <p>
     * ⚠️ 留空时应用照常启动，但<b>保存 AI 配置会直接报错</b> ——
     * 这是刻意的：宁可让管理员看到「密钥没配」，也不能悄悄明文存下去。
     */
    private String secretKey;
}
