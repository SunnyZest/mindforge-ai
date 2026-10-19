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

    /**
     * 下一场笔试/面试提醒时间。
     * <p>
     * ⚠️ 传 null <b>不是</b>「清空提醒」，而是「这一列不动」—— 与「没传该字段」
     * 在反序列化后完全一样，无法区分。要清空请用 {@link #clearNextActionAt}。
     * <p>
     * 该值是派生值：由面试日历里最近一场未完成的日程回写。这里手工覆盖后，
     * 下一次日程增删改会重新按日程算一遍。
     */
    @Schema(description = "下一场笔试/面试提醒时间（有值时直接覆盖；要清空请用 clearNextActionAt）")
    private LocalDateTime nextActionAt;

    /**
     * 显式清空待办提醒。
     * <p>
     * 之所以要这个字段：局部更新语义里 null 已经表示「不修改」，没法再兼职表示「清空」。
     * <p>
     * 优先级：<b>{@code nextActionAt} 有值时它赢</b>，本标志只在 nextActionAt 为空时生效。
     * 这样前端可以无脑带上 {@code clearNextActionAt: true}，而不会跟用户刚选的时间打架。
     */
    @Schema(description = "显式清空待办提醒（nextActionAt 有值时以 nextActionAt 为准）")
    private Boolean clearNextActionAt;

    @Schema(description = "求职跟进备注（HR联系方式、面经复盘要点等）")
    @Size(max = 1000, message = "跟进备注最长 1000 个字符")
    private String crmNotes;
}
