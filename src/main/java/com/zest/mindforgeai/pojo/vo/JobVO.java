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

    @Schema(description = "投递阶段：WISHLIST/APPLIED/SCREENING/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED")
    private String applicationStage;

    @Schema(description = "绑定的目标简历 id")
    private String linkedResumeId;

    @Schema(description = "薪资范围或 Offer 年薪包")
    private String salaryOffer;

    @Schema(description = "下一场笔试/面试提醒时间")
    private LocalDateTime nextActionAt;

    @Schema(description = "求职跟进备注")
    private String crmNotes;

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
        vo.setApplicationStage(job.getApplicationStage() == null ? "WISHLIST" : job.getApplicationStage());
        vo.setLinkedResumeId(job.getLinkedResumeId() == null ? null : String.valueOf(job.getLinkedResumeId()));
        vo.setSalaryOffer(job.getSalaryOffer());
        vo.setNextActionAt(job.getNextActionAt());
        vo.setCrmNotes(job.getCrmNotes());
        vo.setStatus(job.getStatus());
        vo.setCreatedAt(job.getCreatedAt());
        vo.setUpdatedAt(job.getUpdatedAt());
        return vo;
    }
}
