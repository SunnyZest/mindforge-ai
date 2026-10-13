package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 求职 CRM 信息更新请求（投递阶段、绑定简历、薪资包、面试日程提醒、跟进备注）。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "更新职位求职 CRM 信息")
@Data
public class JobCrmUpdateDTO {

    @Schema(description = "投递阶段：WISHLIST / APPLIED / SCREENING / INTERVIEW_1 / INTERVIEW_2 / HR / OFFER / REJECTED")
    @Pattern(
            regexp = "^(WISHLIST|APPLIED|SCREENING|INTERVIEW_1|INTERVIEW_2|HR|OFFER|REJECTED)$",
            message = "不支持的投递阶段"
    )
    private String applicationStage;

    @Schema(description = "绑定的目标简历 id（传空字符串表示解除绑定）")
    @Size(max = 32, message = "简历 ID 长度不合法")
    private String linkedResumeId;

    @Schema(description = "薪资范围或 Offer 年薪包（如 25k*16）")
    @Size(max = 64, message = "薪资描述最长 64 个字符")
    private String salaryOffer;

    @Schema(description = "下一场笔试/面试提醒时间（传 null 表示清空提醒）")
    private LocalDateTime nextActionAt;

    @Schema(description = "求职跟进备注（HR联系方式、面经复盘要点等）")
    @Size(max = 1000, message = "跟进备注最长 1000 个字符")
    private String crmNotes;
}
