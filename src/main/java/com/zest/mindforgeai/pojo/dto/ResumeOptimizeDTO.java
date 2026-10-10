package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 简历 AI 优化，文档 §二十一 {@code POST /api/app/resumes/{id}/optimize}。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "简历 AI 优化")
@Data
public class ResumeOptimizeDTO {

    /**
     * 目标岗位，可空。
     * <p>
     * 传了的话会拿该 JD 的原文做对照 —— 建议会变成「这个岗位看重 X，你这段没写」，
     * 比泛泛的「建议量化成果」有用得多。
     * 不传就按通用标准评（相当于「让这份简历自己更好」）。
     */
    @Schema(description = "目标岗位 id，可空；不传则按通用标准评")
    private String jobId;

    /**
     * 本次重点分析的部分，可空 = 整体。
     * <p>
     * 取值：{@code SUMMARY} / {@code WORK_EXPERIENCE} / {@code PROJECT_EXPERIENCE} /
     * {@code SKILLS} / {@code EDUCATION} / {@code OVERALL}。
     */
    @Schema(description = "重点分析的部分，可空 = 整体",
            example = "PROJECT_EXPERIENCE",
            allowableValues = {"SUMMARY", "WORK_EXPERIENCE", "PROJECT_EXPERIENCE",
                    "SKILLS", "EDUCATION", "OVERALL"})
    private String target;
}
