package com.zest.mindforgeai.pojo.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.pojo.entity.Resume;
import com.zest.mindforgeai.pojo.entity.SysFile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历详情。
 * <p>
 * 比列表项多出：文件 id/名/大小、AI 摘要、解析全文、结构化数据。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "简历详情")
@Data
public class ResumeDetailVO {

    @Schema(description = "简历 id")
    private String id;

    @Schema(description = "简历名称")
    private String name;

    @Schema(description = "目标职位")
    private String targetPosition;

    @Schema(description = "原始文件 id")
    private String fileId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "简历摘要（AI 提炼）")
    private String summary;

    @Schema(description = "原始文本内容（解析后提取，未解析时为 null）")
    private String rawContent;

    /**
     * AI 解析出的结构化简历。
     * <p>
     * 用 {@link JsonNode} 而不是 String —— 库里是 JSON 列，
     * 当字符串返回的话前端收到的是被转义的 JSON（"{\"name\":...}"），还得再 parse 一次。
     */
    @Schema(description = "结构化简历数据（未解析时为 null）")
    private JsonNode structuredJson;

    @Schema(description = "解析状态")
    private String parseStatus;

    @Schema(description = "解析失败原因；非 FAILED 状态时为 null")
    private String errorMessage;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static ResumeDetailVO of(Resume resume, SysFile file, JsonNode structuredJson) {
        ResumeDetailVO vo = new ResumeDetailVO();
        vo.setId(resume.getId() == null ? null : String.valueOf(resume.getId()));
        vo.setName(resume.getName());
        vo.setTargetPosition(resume.getTargetPosition());
        vo.setFileId(resume.getFileId() == null ? null : String.valueOf(resume.getFileId()));
        vo.setFileName(file == null ? null : file.getOriginalName());
        vo.setFileSize(file == null ? null : file.getFileSize());
        vo.setSummary(resume.getSummary());
        vo.setRawContent(resume.getRawContent());
        vo.setStructuredJson(structuredJson);
        vo.setParseStatus(resume.getParseStatus());
        vo.setErrorMessage(resume.getErrorMessage());
        vo.setStatus(resume.getStatus());
        vo.setCreatedAt(resume.getCreatedAt());
        vo.setUpdatedAt(resume.getUpdatedAt());
        return vo;
    }
}
