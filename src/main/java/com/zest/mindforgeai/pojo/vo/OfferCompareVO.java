package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 多 Offer 对比结果。
 * <p>
 * 维度 key 统一用英文短名，前端自己映射中文标签（便于将来做多语言）：
 * {@code salary} 薪资包 / {@code tech} 技术栈发展 / {@code brand} 平台背书 /
 * {@code wlb} 加班强度 / {@code commute} 通勤距离。
 *
 * @author crx
 * @since 2026-10-14
 */
@Schema(description = "多 Offer 对比结果")
@Data
public class OfferCompareVO {

    @Schema(description = "参与对比的 Offer id（回显，顺序同请求）")
    private List<String> offerIds;

    @Schema(description = "归一化后的权重（五个加起来 = 1），前端可直接展示占比")
    private Map<String, Double> weights;

    @Schema(description = "各 Offer 的评分与排名，按加权总分从高到低")
    private List<Item> offers;

    @Schema(description = "各维度的最优 Offer（并列时返回多个），key 是维度英文名")
    private Map<String, List<String>> bestByDimension;

    @Schema(description = "一句话决策建议")
    private String recommendation;

    @Schema(description = "对比项")
    @Data
    public static class Item {

        @Schema(description = "Offer id")
        private String offerId;

        @Schema(description = "公司名")
        private String companyName;

        @Schema(description = "岗位名")
        private String positionName;

        @Schema(description = "年薪（万元）")
        private BigDecimal salaryAnnual;

        @Schema(description = "薪资包评分")
        private Integer salaryScore;

        @Schema(description = "技术栈发展评分")
        private Integer techScore;

        @Schema(description = "平台背书评分")
        private Integer brandScore;

        @Schema(description = "加班强度评分")
        private Integer wlbScore;

        @Schema(description = "通勤距离评分")
        private Integer commuteScore;

        @Schema(description = "状态：PENDING / ACCEPTED / DECLINED")
        private String status;

        @Schema(description = "加权总分（0-10，保留两位小数）")
        private Double totalScore;

        @Schema(description = "排名，从 1 开始（同分并列同名次）")
        private Integer rank;

        @Schema(description = "这家在哪些维度并列最优（维度英文名列表）")
        private List<String> bestDimensions;
    }
}
