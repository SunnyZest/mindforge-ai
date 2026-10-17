package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * AI 薪酬估算表
 * </p>
 * <p>
 * ★ 这张表回答的是「AI 估出来的参考区间」，<b>不是市场行情</b> ——
 * 我们没有真实薪酬样本，所以表里刻意没有「样本量 / 数据来源」这类字段：
 * 编一个数字比不给更糟。
 * <p>
 * ★ <b>一行同时是缓存和历史</b>：{@code requestKey} 是参数指纹，
 * 同一用户 + 同参数在 7 天内直接复用旧行，不重复调模型。
 * <p>
 * ⚠️ 没有 {@code deleted} 列 —— 估算记录只增不改，不需要逻辑删除。
 *
 * @author crx
 * @since 2026-10-17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("salary_estimate")
@Schema(description = "SalaryEstimate对象,AI 薪酬估算表")
public class SalaryEstimate implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属用户 id")
    private Long userId;

    /**
     * 参数指纹 = sha256(city|position|years|education|companyType|排序后的 skills)。
     * <p>
     * 用指纹而不是直接按列比对：参数一多，等值条件就长得没法看，
     * 而且 skills 是数组，逐项比对要拆表。指纹只要求「同样的输入得到同样的串」。
     */
    @Schema(description = "参数指纹，用于 7 天内命中缓存")
    private String requestKey;

    @Schema(description = "城市（用户填写原文）")
    private String city;

    @Schema(description = "岗位名称")
    private String position;

    @Schema(description = "工作年限")
    private Integer yearsOfExperience;

    @Schema(description = "学历（选填）")
    private String education;

    @Schema(description = "公司类型（选填）")
    private String companyType;

    @Schema(description = "技术栈（JSON 数组）")
    private String skillsJson;

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

    /**
     * 置信度。
     * <p>
     * ★ 存的是<b>服务端按规则钳制后</b>的值，不是模型自评 ——
     * 模型普遍过于自信，城市 / 岗位 / 年限不在常见组合里时必须降档。
     * 具体规则见 {@code SalaryEstimateServiceImpl#clampConfidence}。
     */
    @Schema(description = "置信度 LOW/MEDIUM/HIGH，服务端钳制后的值")
    private String confidence;

    @Schema(description = "估算依据（JSON 数组）")
    private String basisJson;

    @Schema(description = "加减分项（JSON 数组）")
    private String factorsJson;

    /**
     * 免责声明。
     * <p>
     * ★ 由服务端固定拼接，<b>不采信模型输出</b> —— 交给模型写的话，
     * 总会有某次生成悄悄把它省掉，而这种「偶尔消失」的免责声明等于没有。
     */
    @Schema(description = "免责声明，服务端固定拼接")
    private String caveat;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
