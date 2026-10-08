package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.Resume;
import com.zest.mindforgeai.pojo.entity.SysFile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历列表项。
 * <p>
 * <b>刻意不含 {@code rawContent} 和 {@code structuredJson}</b> ——
 * 前者是解析出来的全文（longtext），后者是完整的结构化简历，
 * 都是一条记录里最大的两块数据，列表页用不上。只在详情接口返回。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "简历列表项")
@Data
public class ResumeVO {

    @Schema(description = "简历 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "简历名称")
    private String name;

    @Schema(description = "目标职位")
    private String targetPosition;

    @Schema(description = "解析状态：PENDING / PARSING / SUCCESS / FAILED")
    private String parseStatus;

    @Schema(description = "原始文件名（来自 sys_file，文件记录被删时为 null）")
    private String fileName;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /**
     * @param file 对应的 sys_file，可为 null（文件记录已被清理）
     */
    public static ResumeVO of(Resume resume, SysFile file) {
        ResumeVO vo = new ResumeVO();
        vo.setId(resume.getId() == null ? null : String.valueOf(resume.getId()));
        vo.setName(resume.getName());
        vo.setTargetPosition(resume.getTargetPosition());
        vo.setParseStatus(resume.getParseStatus());
        vo.setFileName(file == null ? null : file.getOriginalName());
        vo.setFileSize(file == null ? null : file.getFileSize());
        vo.setStatus(resume.getStatus());
        vo.setCreatedAt(resume.getCreatedAt());
        vo.setUpdatedAt(resume.getUpdatedAt());
        return vo;
    }
}
