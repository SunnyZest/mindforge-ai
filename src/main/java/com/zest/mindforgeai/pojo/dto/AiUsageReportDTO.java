package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * AI 使用量上报（Python → Java）。
 *
 * <p>为什么是 Python 推、而不是 Java 记：<b>只有拿到模型响应的一方才看得到 token 数</b>。
 * Java 只知道「我调了一次 Python」，不知道这次消耗了多少。
 *
 * <p>字段名 camelCase，对齐 Python 侧 {@code ai_usage} 的输出键。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "AI 使用量上报")
public record AiUsageReportDTO(

        @Schema(description = "触发用户 id；系统自动调用时为空")
        String userId,

        @Schema(description = "链路 id，Java 通过 X-Trace-Id 头传给 Python，这里原样带回来")
        String requestId,

        @Schema(description = "业务场景：RAG_CHAT / RESUME_PARSE / ...")
        @NotBlank(message = "scene 不能为空")
        String scene,

        @Schema(description = "模型提供方")
        String provider,

        @Schema(description = "模型名")
        String modelName,

        @Schema(description = "输入 token 数")
        Integer promptTokens,

        @Schema(description = "输出 token 数")
        Integer completionTokens,

        @Schema(description = "总 token 数")
        Integer totalTokens,

        @Schema(description = "调用耗时（毫秒）")
        Long durationMs,

        @Schema(description = "SUCCESS / FAILED")
        @NotBlank(message = "status 不能为空")
        String status,

        @Schema(description = "失败原因")
        String errorMessage
) {
}
