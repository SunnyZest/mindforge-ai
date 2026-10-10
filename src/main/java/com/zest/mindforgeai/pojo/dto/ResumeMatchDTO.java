package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 简历-JD 匹配分析请求，文档 §二十一 {@code POST /api/app/resume-job-analysis}。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "简历-JD 匹配分析")
@Data
public class ResumeMatchDTO {

    @Schema(description = "简历 id")
    @NotBlank(message = "简历 id 不能为空")
    private String resumeId;

    @Schema(description = "职位 id")
    @NotBlank(message = "职位 id 不能为空")
    private String jobId;
}
