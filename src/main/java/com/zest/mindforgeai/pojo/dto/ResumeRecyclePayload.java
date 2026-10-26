package com.zest.mindforgeai.pojo.dto;

import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import com.zest.mindforgeai.pojo.entity.ResumeSkill;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 简历回收站的 payload 快照。
 *
 * <p>删简历时：{@code resume} 逻辑删、{@code sys_file} 软删，
 * 而 {@code resume_skill} / {@code resume_job_analysis} 是**物理删**（两张表都没有 deleted 列）。
 *
 * <p>★ 简历比文档简单：正文与解析结果都在主表的 {@code structured_json} 里，逻辑删不碰它，
 * 所以**不需要重新解析** —— 把技能和匹配分析插回去就完整了。
 *
 * <p>{@code fileId} 单独存，用于恢复 {@code sys_file}（把 {@code deleted_at} 清空，
 * 否则 FilePurgeTask 会按保留期把 OSS 对象真删掉）。
 *
 * @author crx
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "简历回收站快照")
public class ResumeRecyclePayload {

    @Schema(description = "技能（删除时被物理删掉的行）")
    private List<ResumeSkill> skills;

    @Schema(description = "简历-JD 匹配分析（删除时被物理删掉的行）")
    private List<ResumeJobAnalysis> analyses;

    @Schema(description = "原始文件 id，用于恢复 sys_file")
    private Long fileId;
}
