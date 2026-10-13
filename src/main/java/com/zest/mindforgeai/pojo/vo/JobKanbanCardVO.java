package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 求职投递看板卡片项。
 * <p>
 * 聚合职位基础信息、CRM 投递阶段、关联简历、最新简历匹配度及模拟面试综合得分。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "求职投递看板卡片")
@Data
public class JobKanbanCardVO {

    @Schema(description = "职位 id（19位雪花字符串）")
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

    @Schema(description = "JD 解析状态：PENDING / PARSING / SUCCESS / FAILED")
    private String parseStatus;

    @Schema(description = "投递阶段：WISHLIST / APPLIED / SCREENING / INTERVIEW_1 / INTERVIEW_2 / HR / OFFER / REJECTED")
    private String applicationStage;

    @Schema(description = "绑定的目标简历 id（可为空）")
    private String linkedResumeId;

    @Schema(description = "绑定的目标简历名称（可为空）")
    private String linkedResumeName;

    @Schema(description = "薪资范围或 Offer 年薪包（如 25k*16）")
    private String salaryOffer;

    @Schema(description = "下一场笔试/面试提醒时间")
    private LocalDateTime nextActionAt;

    @Schema(description = "求职跟进备注")
    private String crmNotes;

    @Schema(description = "与该岗位最新的简历匹配度综合得分（0~100，未分析时为 null）")
    private Double matchScore;

    @Schema(description = "针对该岗位的模拟面试累计场次")
    private Integer interviewCount;

    @Schema(description = "针对该岗位的模拟面试历史最高分（未面试时为 null）")
    private Double bestInterviewScore;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
