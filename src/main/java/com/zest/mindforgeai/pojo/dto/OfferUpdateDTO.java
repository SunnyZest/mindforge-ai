package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改 Offer。字段全部可空 —— 只更新传了的那部分。
 *
 * @author crx
 * @since 2026-10-14
 */
@Schema(description = "修改 Offer")
@Data
public class OfferUpdateDTO {

    @Schema(description = "公司名，可空 = 不改")
    @Size(max = 255, message = "公司名不能超过 255 个字符")
    private String companyName;

    @Schema(description = "岗位名，可空 = 不改")
    @Size(max = 255, message = "岗位名不能超过 255 个字符")
    private String positionName;

    @Schema(description = "年薪（万元），可空 = 不改")
    @DecimalMin(value = "0", message = "年薪不能为负")
    @Digits(integer = 8, fraction = 2, message = "年薪最多 8 位整数 + 2 位小数")
    private BigDecimal salaryAnnual;

    @Schema(description = "薪资包评分 1-10，可空 = 不改")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer salaryScore;

    @Schema(description = "技术栈发展评分 1-10，可空 = 不改")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer techScore;

    @Schema(description = "平台背书评分 1-10，可空 = 不改")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer brandScore;

    @Schema(description = "加班强度评分 1-10，可空 = 不改")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer wlbScore;

    @Schema(description = "通勤距离评分 1-10，可空 = 不改")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer commuteScore;

    @Schema(description = "各维度打分的理由，JSON 对象，可空 = 不改")
    @Size(max = 2000, message = "打分理由不能超过 2000 个字符")
    private String dimensionNotes;
}
