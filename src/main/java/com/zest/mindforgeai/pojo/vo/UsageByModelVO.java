package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 按「场景 + 模型」的 AI 使用量（管理端）。
 *
 * <p>分组键是 scene + provider + model_name 三者 —— 只按模型名分会把
 * 「同一个模型用于对话」和「用于解析」混在一起，而这两者的 token 量级差一个数量级。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "按模型的 AI 使用量")
public class UsageByModelVO {

    @Schema(description = "业务场景")
    private String scene;

    @Schema(description = "模型提供方")
    private String provider;

    @Schema(description = "模型名")
    private String modelName;

    @Schema(description = "调用次数")
    private Long requests;

    @Schema(description = "成功次数")
    private Long successCount;

    @Schema(description = "失败次数")
    private Long failedCount;

    @Schema(description = "输入 token 合计")
    private Long promptTokens;

    @Schema(description = "输出 token 合计")
    private Long completionTokens;

    @Schema(description = "token 合计")
    private Long totalTokens;

    @Schema(description = "平均耗时（毫秒）")
    private Long avgDurationMs;
}
