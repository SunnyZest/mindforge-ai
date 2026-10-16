package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新建 / 修改求职日程的请求。
 * <p>
 * ★ <b>为什么新建和修改共用一个 DTO</b>：两者的字段与校验规则<b>完全相同</b>
 * （修改是整体覆盖，不是局部更新）。拆成两个一模一样的类只会让校验规则有地方漏改。
 * 对比 Offer 那边拆开是因为它的修改是「只传要改的字段」的局部语义，规则不同。
 *
 * @author crx
 * @since 2026-10-16
 */
@Schema(description = "新建或修改求职日程")
@Data
public class JobEventSaveDTO {

    @Schema(description = "关联职位 id（必填，19 位雪花字符串）")
    @NotBlank(message = "请选择关联职位")
    @Size(max = 32, message = "职位 ID 长度不合法")
    private String jobId;

    @Schema(description = "类型：WRITTEN_TEST / INTERVIEW_1 / INTERVIEW_2 / HR / OFFER_TALK / OTHER")
    @NotBlank(message = "请选择日程类型")
    @Pattern(
            regexp = "^(WRITTEN_TEST|INTERVIEW_1|INTERVIEW_2|HR|OFFER_TALK|OTHER)$",
            message = "不支持的日程类型"
    )
    private String eventType;

    @Schema(description = "标题（如「字节跳动 技术一面」）")
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题最长 255 个字符")
    private String title;

    @Schema(description = "开始时间")
    @NotNull(message = "请选择开始时间")
    private LocalDateTime eventAt;

    @Schema(description = "预计时长（分钟），可空")
    @Min(value = 0, message = "时长不能为负")
    @Max(value = 1440, message = "时长最长 1440 分钟（24 小时）")
    private Integer durationMinutes;

    @Schema(description = "形式：ONSITE / ONLINE / PHONE，可空")
    @Pattern(regexp = "^$|^(ONSITE|ONLINE|PHONE)$", message = "不支持的面试形式")
    private String eventMode;

    @Schema(description = "地点或会议链接，可空")
    @Size(max = 500, message = "地点最长 500 个字符")
    private String location;

    @Schema(description = "备注（面试官、准备要点），可空")
    @Size(max = 1000, message = "备注最长 1000 个字符")
    private String note;

    @Schema(description = "状态：PENDING / DONE / CANCELLED，不传默认 PENDING")
    @Pattern(regexp = "^$|^(PENDING|DONE|CANCELLED)$", message = "不支持的日程状态")
    private String status;
}
