package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI 使用量总览（管理端）。
 *
 * <p>token 和耗时都是 0 而不是 null —— 一条记录都没有时 SUM/AVG 返回 NULL，
 * 前端拿到 null 会显示成空白，0 才是「确实没有」。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "AI 使用量总览")
public class UsageSummaryVO {

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
