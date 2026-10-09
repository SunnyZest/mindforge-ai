package com.zest.mindforgeai.pojo.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.pojo.entity.Resume;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历解析结果，文档 §十九 {@code GET /api/app/resumes/{id}/parsed}。
 * <p>
 * 比 {@link ResumeDetailVO} 窄：只回解析相关的东西，不带文件信息。
 * 前端轮询「解析好了没」时用这个 —— 响应体小，适合高频调用。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "简历解析结果")
@Data
public class ResumeParsedVO {

    @Schema(description = "简历 id")
    private String resumeId;

    @Schema(description = "解析状态：PENDING / PARSING / SUCCESS / FAILED")
    private String parseStatus;

    @Schema(description = "简历摘要（AI 提炼，未解析时为 null）")
    private String summary;

    @Schema(description = "结构化简历数据（未解析时为 null）")
    private JsonNode structuredJson;

    @Schema(description = "解析失败原因；非 FAILED 状态时为 null")
    private String errorMessage;

    @Schema(description = "最后更新时间 —— 前端可据此判断解析是否推进过")
    private LocalDateTime updatedAt;

    public static ResumeParsedVO of(Resume resume, JsonNode structuredJson) {
        ResumeParsedVO vo = new ResumeParsedVO();
        vo.setResumeId(resume.getId() == null ? null : String.valueOf(resume.getId()));
        vo.setParseStatus(resume.getParseStatus());
        vo.setSummary(resume.getSummary());
        vo.setStructuredJson(structuredJson);
        vo.setErrorMessage(resume.getErrorMessage());
        vo.setUpdatedAt(resume.getUpdatedAt());
        return vo;
    }
}
