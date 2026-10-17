package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 薪酬估算结果。
 * <p>
 * ★ 所有字段名都刻意避开「市场 / 行情 / 样本」这类词 ——
 * 我们给的是 AI 按岗位画像推算的参考区间，不是统计出来的行情。
 * 命名上让人少误会一点，比事后解释有用。
 *
 * @author crx
 * @since 2026-10-17
 */
@Schema(description = "薪酬估算结果")
@Data
public class SalaryEstimateVO {

    @Schema(description = "估算记录 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "城市")
    private String city;

    @Schema(description = "岗位")
    private String position;

    @Schema(description = "工作年限")
    private Integer yearsOfExperience;

    @Schema(description = "学历")
    private String education;

    @Schema(description = "公司类型")
    private String companyType;

    @Schema(description = "技术栈")
    private List<String> skills;

    @Schema(description = "用户填写的当前年包（元）")
    private BigDecimal currentAnnualSalary;

    @Schema(description = "月薪 P50（元）")
    private BigDecimal monthlyP50;

    @Schema(description = "月薪 P75（元）")
    private BigDecimal monthlyP75;

    @Schema(description = "月薪 P90（元）")
    private BigDecimal monthlyP90;

    @Schema(description = "年包 P50（元）")
    private BigDecimal annualP50;

    @Schema(description = "年包 P75（元）")
    private BigDecimal annualP75;

    @Schema(description = "年包 P90（元）")
    private BigDecimal annualP90;

    @Schema(description = "一年发几个月")
    private Integer monthsPerYear;

    @Schema(description = "置信度 LOW/MEDIUM/HIGH")
    private String confidence;

    @Schema(description = "估算依据")
    private List<String> basis;

    @Schema(description = "加减分项")
    private List<String> factors;

    @Schema(description = "免责声明（服务端固定文案）")
    private String caveat;

    /**
     * 是否命中 7 天缓存。
     * <p>
     * 前端可以据此提示「参数没变，直接给你上次的结果」——
     * 不然用户会以为每次点都重新算过，反而怀疑结果为什么一样。
     */
    @Schema(description = "是否命中缓存（同参数 7 天内复用）")
    private Boolean cached;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
