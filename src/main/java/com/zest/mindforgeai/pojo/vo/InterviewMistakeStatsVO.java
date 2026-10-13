package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 面试错题本统计概览。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "面试错题本统计概览")
@Data
public class InterviewMistakeStatsVO {

    @Schema(description = "已结束面试中累计已作答总题数")
    private Long totalAnswered;

    @Schema(description = "待攻克薄弱题数（得分 < 75）")
    private Long weakCount;

    @Schema(description = "已掌握题数（得分 >= 75）")
    private Long masteredCount;

    @Schema(description = "累计答题平均分（0~100）")
    private Double averageScore;
}
