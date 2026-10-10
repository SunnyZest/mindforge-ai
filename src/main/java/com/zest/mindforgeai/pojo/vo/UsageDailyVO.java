package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 按天的 AI 使用量（管理端）。
 *
 * <p>⚠️ 只返回<b>有记录的天</b>，不补 0。前端画折线图时自己补空档 ——
 * 后端补的话要先在 Java 里生成一段连续日期再和查询结果做 join，
 * 为一个展示细节引入这些代码不划算。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "按天的 AI 使用量")
public class UsageDailyVO {

    @Schema(description = "日期")
    private LocalDate date;

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
}
