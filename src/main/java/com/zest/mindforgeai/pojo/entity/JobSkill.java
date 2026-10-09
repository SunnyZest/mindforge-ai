package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 职位技能要求表
 * </p>
 * <p>
 * 数据来源是 JD 解析（{@code POST /api/app/jobs/{id}/parse}）。
 * 和 {@link ResumeSkill} 是配套的一对：一张记「候选人会什么」，
 * 一张记「岗位要什么」，匹配时按 skill_name 对齐。
 * <p>
 * ⚠️ <b>没有 {@code deleted} 列</b> —— 理由同 {@link ResumeSkill}：
 * 重新解析时全删全插，不做单条逻辑删除。
 *
 * @author crx
 * @since 2026-10-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("job_skill")
@Schema(description = "JobSkill对象,职位技能要求表")
public class JobSkill implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属职位 id，逻辑关联 job.id")
    private Long jobId;

    @Schema(description = "技能名称")
    private String skillName;

    @Schema(description = "技能类型：LANGUAGE / FRAMEWORK / TOOL / SOFT")
    private String skillType;

    /**
     * 要求程度。
     * <p>
     * 保留 JD 里的原话（「精通」「熟悉」「了解」），不换算成枚举 ——
     * 招聘方的用词本身就是信息，换算成 ADVANCED 这类枚举反而丢掉了差异。
     */
    @Schema(description = "要求程度，如 了解 / 熟悉 / 精通")
    private String requiredLevel;

    /**
     * 是否硬性要求。
     * <p>
     * 列是 {@code tinyint}，所以用 {@link Integer}（1 必须 / 0 加分项）——
     * 和本项目其它 tinyint 字段（status / deleted）保持一致。
     */
    @Schema(description = "是否硬性要求：1 必须 / 0 加分项")
    private Integer required;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
