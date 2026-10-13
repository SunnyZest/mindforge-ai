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
 * 简历职位匹配分析表
 * </p>
 * <p>
 * ⚠️ 这张表<b>没有 {@code deleted} 列</b> —— 删除只能物理删。
 * <p>
 * ⚠️ 同一对（简历 + 职位）<b>只保留一条</b>：重新匹配会覆盖上一条
 * （见 {@code ResumeJobAnalysisServiceImpl} 里的说明）。
 * 表上的 {@code idx_analysis_resume_job} 不是唯一索引，所以这个约束靠代码保证。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("resume_job_analysis")
@Schema(description = "ResumeJobAnalysis对象,简历职位匹配分析表")
public class ResumeJobAnalysis implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属用户 id")
    private Long userId;

    @Schema(description = "简历 id")
    private Long resumeId;

    @Schema(description = "职位 id")
    private Long jobId;

    /**
     * 综合匹配分（0~100）。
     * <p>
     * ⚠️ <b>仅作展示辅助，不参与任何准入判断</b> ——
     * 真正能指导用户的是 {@code matchItemsJson} 里的五类结论。
     */
    @Schema(description = "综合匹配分（0~100）；仅展示辅助")
    private BigDecimal overallScore;

    @Schema(description = "匹配上的技能（JSON 数组）")
    private String matchedSkillsJson;

    @Schema(description = "缺失的技能（JSON 数组）")
    private String missingSkillsJson;

    @Schema(description = "匹配上的经历（JSON 数组）")
    private String matchedExperienceJson;

    @Schema(description = "缺失的经历（JSON 数组）")
    private String missingExperienceJson;

    /**
     * ★ 逐条要求的匹配结论 —— 这张表的<b>核心字段</b>。
     * <p>
     * 数组，每项形如
     * {@code {category, requirement, verdict, evidence, advice}}，
     * 其中 {@code verdict} 是五类结论之一：
     * {@code MATCHED}（已匹配）/ {@code EXPRESSION_GAP}（表达缺口）/
     * {@code INSUFFICIENT_EVIDENCE}（证据不足）/ {@code REAL_GAP}（真实缺口）/
     * {@code UNCERTAIN}（待确认）。
     * <p>
     * 为什么不用百分比：百分比没法行动。「73%」说了等于没说，
     * 而「表达缺口：把『负责订单系统开发』改成带量级的说法」可以直接照着改。
     */
    @Schema(description = "逐条要求的匹配结论（五类），JSON 数组")
    private String matchItemsJson;

    @Schema(description = "改进建议（JSON 数组）")
    private String suggestionsJson;

    @Schema(description = "AI 生成的整体分析（整体判断 + 五类统计 + 优先动作）")
    private String analysisText;

    /**
     * 使用的模型名。
     * <p>
     * 用的哪个模型由 Python 侧的配置决定，Java 看不到 —— 留 null 比瞎填一个强。
     */
    @Schema(description = "使用的模型名")
    private String modelName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
