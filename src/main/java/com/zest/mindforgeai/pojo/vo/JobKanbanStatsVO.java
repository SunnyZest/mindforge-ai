package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 求职投递看板顶部统计概览。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "求职投递看板统计概览")
@Data
public class JobKanbanStatsVO {

    @Schema(description = "总跟进岗位数")
    private Long totalTracked;

    @Schema(description = "已投递及后续阶段总数")
    private Long appliedCount;

    @Schema(description = "面试进行中数量（一面 + 二面 + HR面）")
    private Long interviewingCount;

    @Schema(description = "已获 Offer 数量")
    private Long offerCount;

    @Schema(description = "未来 7 天内有面试/笔试日程提醒的数量")
    private Long upcomingScheduleCount;
}
