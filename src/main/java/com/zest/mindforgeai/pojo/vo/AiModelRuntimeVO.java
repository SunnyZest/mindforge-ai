package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * AI 模型配置的<b>运行时视图</b> —— 只给内部接口用，含明文 API Key。
 *
 * <p>⚠️ 和 {@link AiModelVO} 的区别就一条：<b>这个带 apiKey</b>。
 * {@code AiModelVO} 是给管理端看的，永远只回 {@code apiKeyHint}（****abcd）；
 * 而真正调模型的是 Python 服务，它必须拿到能用的 Key —— 所以单独开一个视图，
 * 只在 {@code /internal/**}（服务令牌保护）下暴露，绝不出现在任何面向用户的接口上。
 *
 * <p>字段名是 camelCase，对齐 Python 侧 {@code ai_config} 的读取逻辑。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "AI 模型运行时配置（含明文 Key，仅内部接口）")
public record AiModelRuntimeVO(

        @Schema(description = "配置 id")
        String id,

        @Schema(description = "用途：CHAT / EMBEDDING / RERANK")
        String scene,

        @Schema(description = "提供方")
        String provider,

        @Schema(description = "模型名")
        String modelName,

        @Schema(description = "OpenAI 兼容协议的 base_url")
        String baseUrl,

        @Schema(description = "明文 API Key；配置里没填 Key 时为 null（例如本地模型）")
        String apiKey,

        @Schema(description = "采样温度")
        BigDecimal temperature,

        @Schema(description = "单次最大输出 token")
        Integer maxTokens,

        @Schema(description = "调用超时（秒）")
        Integer timeoutSeconds,

        @Schema(description = "同 scene 下的默认配置")
        Boolean isDefault
) {
}
