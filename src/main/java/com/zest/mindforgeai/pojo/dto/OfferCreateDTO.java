package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建 Offer。
 *
 * @author crx
 * @since 2026-10-14
 */
@Schema(description = "创建 Offer")
@Data
public class OfferCreateDTO {

    @Schema(description = "关联的职位 id，可空（从投递看板导入时带上）")
    @Size(max = 64, message = "职位 id 长度不能超过 64 个字符")
    private String jobId;

    @Schema(description = "公司名")
    @NotBlank(message = "公司名不能为空")
    @Size(max = 255, message = "公司名不能超过 255 个字符")
    private String companyName;

    @Schema(description = "岗位名，可空")
    @Size(max = 255, message = "岗位名不能超过 255 个字符")
    private String positionName;

    /**
     * 年薪（万元）。只用于展示参考，不参与算分 ——
     * 打分表的核心是用户的主观权衡，见 {@code JobOffer} 的说明。
     */
    @Schema(description = "年薪（万元），仅展示参考")
    @DecimalMin(value = "0", message = "年薪不能为负")
    @Digits(integer = 8, fraction = 2, message = "年薪最多 8 位整数 + 2 位小数")
    private BigDecimal salaryAnnual;

    @Schema(description = "薪资包评分 1-10")
    @NotNull(message = "薪资包评分不能为空")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer salaryScore;

    @Schema(description = "技术栈发展评分 1-10")
    @NotNull(message = "技术栈发展评分不能为空")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer techScore;

    @Schema(description = "平台背书评分 1-10")
    @NotNull(message = "平台背书评分不能为空")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer brandScore;

    @Schema(description = "加班强度评分 1-10（分越高越轻松）")
    @NotNull(message = "加班强度评分不能为空")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer wlbScore;

    @Schema(description = "通勤距离评分 1-10（分越高越方便）")
    @NotNull(message = "通勤距离评分不能为空")
    @Min(value = 1, message = "评分最小为 1")
    @Max(value = 10, message = "评分最大为 10")
    private Integer commuteScore;

    @Schema(description = "各维度打分的理由，JSON 对象，如 {\"salary\":\"总包最高\",\"wlb\":\"大小周\"}")
    @Size(max = 2000, message = "打分理由不能超过 2000 个字符")
    private String dimensionNotes;
}
