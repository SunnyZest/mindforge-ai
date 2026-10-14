package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 多 Offer 对比请求。
 * <p>
 * ★ <b>权重刻意不落库、由请求带过来</b>：权重表达的是「你现在最看重什么」，
 * 它会随心境变（今天觉得钱重要，明天觉得 WLB 重要）。落库就得设计一套
 * 「改权重」的接口和并发问题；放在请求里则拖一下滑块就能实时看排名变化。
 *
 * @author crx
 * @since 2026-10-14
 */
@Schema(description = "多 Offer 对比请求")
@Data
public class OfferCompareDTO {

    @Schema(description = "要比对的 Offer id 列表（2~5 个，字符串避免 JS 精度丢失）")
    @NotEmpty(message = "至少选择 2 个 Offer 才能对比")
    @Size(min = 2, max = 5, message = "一次可以对比 2~5 个 Offer")
    private List<String> offerIds;

    /**
     * 五个维度的权重，都用 0-100 的数值（前端滑块直观）。
     * 全都不传 = 五维等权；传了的部分会被归一化，不必凑成 100。
     * ⚠️ 全传 0 会被拒绝（没法归一化）。
     */
    @Schema(description = "薪资包权重 0-100，不传 = 等权")
    @DecimalMin(value = "0", message = "权重不能为负")
    @DecimalMax(value = "100", message = "权重最大为 100")
    private Double salaryWeight;

    @Schema(description = "技术栈发展权重 0-100")
    @DecimalMin(value = "0", message = "权重不能为负")
    @DecimalMax(value = "100", message = "权重最大为 100")
    private Double techWeight;

    @Schema(description = "平台背书权重 0-100")
    @DecimalMin(value = "0", message = "权重不能为负")
    @DecimalMax(value = "100", message = "权重最大为 100")
    private Double brandWeight;

    @Schema(description = "加班强度权重 0-100")
    @DecimalMin(value = "0", message = "权重不能为负")
    @DecimalMax(value = "100", message = "权重最大为 100")
    private Double wlbWeight;

    @Schema(description = "通勤距离权重 0-100")
    @DecimalMin(value = "0", message = "权重不能为负")
    @DecimalMax(value = "100", message = "权重最大为 100")
    private Double commuteWeight;
}
