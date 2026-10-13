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
 * 简历技能表
 * </p>
 * <p>
 * 数据来源是简历解析（{@code POST /api/app/resumes/{id}/parse}）——
 * 从 {@code resume.structured_json} 里把 skills 拆出来单独存一份，
 * 是为了后面「简历-JD 匹配」能直接按技能名 JOIN，不用每次去解析 JSON。
 * <p>
 * ⚠️ <b>没有 {@code deleted} 列</b> —— 删除只能物理删。
 * 重新解析时是「先按 resume_id 全删、再全量插入」：
 * 技能是解析结果的派生物，没有「单独删某一条技能」这个业务动作，
 * 留逻辑删除只会让「已删的旧技能」和「新技能」在表里共存、还得处处过滤。
 *
 * @author crx
 * @since 2026-10-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("resume_skill")
@Schema(description = "ResumeSkill对象,简历技能表")
public class ResumeSkill implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属简历 id，逻辑关联 resume.id")
    private Long resumeId;

    @Schema(description = "技能名称，如 Java / Spring Boot")
    private String skillName;

    @Schema(description = "技能类型：LANGUAGE / FRAMEWORK / TOOL / SOFT")
    private String skillType;

    @Schema(description = "熟练程度：BEGINNER / INTERMEDIATE / ADVANCED / EXPERT")
    private String proficiency;

    /**
     * 使用年限。
     * <p>
     * 用 {@link BigDecimal} 而不是 Double：列是 {@code decimal(4,1)}，
     * 用浮点会在写入时产生 3.4999999 这种值，再读出来就不是原来那个数了。
     */
    @Schema(description = "使用年限，如 3.5")
    private BigDecimal yearsExperience;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
