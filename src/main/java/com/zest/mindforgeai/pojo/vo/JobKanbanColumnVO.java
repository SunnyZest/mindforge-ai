package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 求职投递看板单列数据。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "求职投递看板列")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobKanbanColumnVO {

    @Schema(description = "阶段编码：WISHLIST / APPLIED / SCREENING / INTERVIEW_1 / INTERVIEW_2 / HR / OFFER / REJECTED")
    private String stage;

    @Schema(description = "阶段中文名称")
    private String title;

    @Schema(description = "该列下的岗位数量")
    private Integer count;

    @Schema(description = "该列下的岗位卡片列表")
    private List<JobKanbanCardVO> cards;
}
