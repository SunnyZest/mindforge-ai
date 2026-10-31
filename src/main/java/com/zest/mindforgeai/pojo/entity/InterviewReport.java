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
 * 面试复盘报告表
 * </p>
 * <p>
 * 一场面试最多一份（表上有 {@code uk_report_interview}）。
 * ⚠️ 没有 {@code deleted} 列，删面试时物理删。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("interview_report")
@Schema(description = "InterviewReport对象,面试复盘报告表")
public class InterviewReport implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属面试 id")
    private Long interviewId;

    @Schema(description = "综合得分（0~100）")
    private BigDecimal overallScore;

    @Schema(description = "技术能力得分")
    private BigDecimal technicalScore;

    @Schema(description = "沟通表达得分")
    private BigDecimal communicationScore;

    @Schema(description = "问题解决得分")
    private BigDecimal problemSolvingScore;

    /**
     * 工程规范得分（0~100）。
     * <p>
     * ⚠️ 可空：2026-10-30 之前的报告没有这个维度，历史数据是 NULL。
     * 不做回填 —— 补分只能重跑模型，成本高且结果不稳定。
     */
    @Schema(description = "工程规范得分（0~100）")
    private BigDecimal engineeringScore;

    /** 岗位契合得分（0~100）。没指定目标岗位时为空 —— 没有岗位就没有契合度可言 */
    @Schema(description = "岗位契合得分（0~100）")
    private BigDecimal jobFitScore;

    @Schema(description = "优势（JSON 数组）")
    private String strengthsJson;

    @Schema(description = "不足（JSON 数组）")
    private String weaknessesJson;

    @Schema(description = "改进建议（JSON 数组）")
    private String suggestionsJson;

    @Schema(description = "AI 生成的完整报告（Markdown）")
    private String reportText;

    @Schema(description = "使用的模型名")
    private String modelName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
