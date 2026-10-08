package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.Job;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 职位 JD 列表项。
 * <p>
 * <b>刻意不含 {@code rawContent}（JD 正文）</b> —— 那是一段可能上千字的 longtext，
 * 列表一页 20 条就是几万字，前端也用不上。正文只在详情接口返回。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "职位 JD 列表项")
@Data
public class JobVO {

    @Schema(description = "职位 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "职位名称")
    private String title;

    @Schema(description = "公司名称")
    private String companyName;

    @Schema(description = "工作地点")
    private String location;

    @Schema(description = "经验要求")
    private String experienceRequired;

    @Schema(description = "学历要求")
    private String educationRequired;

    @Schema(description = "解析状态：PENDING / PARSING / SUCCESS / FAILED")
    private String parseStatus;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static JobVO of(Job job) {
        JobVO vo = new JobVO();
        vo.setId(job.getId() == null ? null : String.valueOf(job.getId()));
        vo.setTitle(job.getTitle());
        vo.setCompanyName(job.getCompanyName());
        vo.setLocation(job.getLocation());
        vo.setExperienceRequired(job.getExperienceRequired());
        vo.setEducationRequired(job.getEducationRequired());
        vo.setParseStatus(job.getParseStatus());
        vo.setStatus(job.getStatus());
        vo.setCreatedAt(job.getCreatedAt());
        vo.setUpdatedAt(job.getUpdatedAt());
        return vo;
    }
}
