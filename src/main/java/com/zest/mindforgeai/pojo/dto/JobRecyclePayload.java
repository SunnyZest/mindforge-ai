package com.zest.mindforgeai.pojo.dto;

import com.zest.mindforgeai.pojo.entity.JobSkill;
import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 职位回收站的 payload 快照。
 *
 * <p>★ 为什么职位需要快照，而 Offer / 面试日程不需要：
 * 删职位时 {@code job_skill} 与 {@code resume_job_analysis} 是**物理删**的
 * （两张表都没有 deleted 列），删完就真的没了 —— 只把 {@code job.deleted} 改回 0
 * 恢复出来会是一个「没有技能要求、没有匹配分析」的空职位。
 *
 * <p>{@code job_event} 是逻辑删的，所以只记 id，恢复时精确改回 0 ——
 * 不能按 {@code job_id} 全量恢复，那样会把用户之前单独删掉的日程也一起捞回来。
 *
 * @author crx
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "职位回收站快照")
public class JobRecyclePayload {

    @Schema(description = "技能要求（删除时被物理删掉的行）")
    private List<JobSkill> skills;

    @Schema(description = "简历-JD 匹配分析（删除时被物理删掉的行）")
    private List<ResumeJobAnalysis> analyses;

    @Schema(description = "删除时被级联逻辑删掉的日程 id，恢复时只还原这些")
    private List<Long> eventIds;
}
