package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改职位 JD。
 * <p>
 * 所有字段都可空：<b>传 null 表示不修改该字段</b>（局部更新）。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "修改职位 JD")
@Data
public class JobUpdateDTO {

    @Schema(description = "职位名称")
    @Size(max = 255, message = "职位名称最长 255 个字符")
    private String title;

    @Schema(description = "公司名称")
    @Size(max = 255, message = "公司名称最长 255 个字符")
    private String companyName;

    @Schema(description = "工作地点")
    @Size(max = 255, message = "工作地点最长 255 个字符")
    private String location;

    @Schema(description = "职位来源链接")
    @Size(max = 1000, message = "来源链接最长 1000 个字符")
    @Pattern(regexp = "^$|^https?://.+", message = "来源链接必须以 http:// 或 https:// 开头")
    private String sourceUrl;

    @Schema(description = "经验要求")
    @Size(max = 128, message = "经验要求最长 128 个字符")
    private String experienceRequired;

    @Schema(description = "学历要求")
    @Size(max = 128, message = "学历要求最长 128 个字符")
    private String educationRequired;

    @Schema(description = "JD 正文")
    @Size(max = 50000, message = "职位描述最长 50000 个字符")
    private String jobDescription;

    @Schema(description = "投递阶段：WISHLIST/APPLIED/SCREENING/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED")
    @Pattern(
            regexp = "^$|^(WISHLIST|APPLIED|SCREENING|INTERVIEW_1|INTERVIEW_2|HR|OFFER|REJECTED)$",
            message = "不支持的投递阶段"
    )
    private String applicationStage;

    @Schema(description = "绑定的目标简历 id")
    @Size(max = 32, message = "简历 ID 长度不合法")
    private String linkedResumeId;

    @Schema(description = "薪资范围或 Offer 年薪包")
    @Size(max = 64, message = "薪资描述最长 64 个字符")
    private String salaryOffer;

    /** 下一场笔试/面试提醒时间。传 null 表示不修改，要清空请用 {@link #clearNextActionAt} */
    @Schema(description = "下一场笔试/面试提醒时间（有值时直接覆盖；要清空请用 clearNextActionAt）")
    private java.time.LocalDateTime nextActionAt;

    /**
     * 显式清空待办提醒，理由同 {@link JobCrmUpdateDTO#getClearNextActionAt()}。
     * <p>
     * 优先级：nextActionAt 有值时它赢，本标志只在 nextActionAt 为空时生效。
     */
    @Schema(description = "显式清空待办提醒（nextActionAt 有值时以 nextActionAt 为准）")
    private Boolean clearNextActionAt;

    @Schema(description = "求职跟进备注")
    @Size(max = 1000, message = "跟进备注最长 1000 个字符")
    private String crmNotes;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;
}
