package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 求职日程项。
 * <p>
 * 除日程本身外，还带上了所属职位的公司名 / 岗位名 / 投递阶段 ——
 * 日历上只显示「技术一面」看不出是哪家，必须把职位上下文一起给前端。
 *
 * @author crx
 * @since 2026-10-16
 */
@Schema(description = "求职日程")
@Data
public class JobEventVO {

    @Schema(description = "日程 id（19位雪花字符串）")
    private String id;

    @Schema(description = "关联职位 id")
    private String jobId;

    @Schema(description = "关联职位名称（职位已被删除时为 null）")
    private String jobTitle;

    @Schema(description = "公司名称")
    private String companyName;

    @Schema(description = "该职位的投递阶段（用于日历上给日程着色/分组）")
    private String applicationStage;

    @Schema(description = "类型：WRITTEN_TEST / INTERVIEW_1 / INTERVIEW_2 / HR / OFFER_TALK / OTHER")
    private String eventType;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "开始时间")
    private LocalDateTime eventAt;

    @Schema(description = "结束时间（= 开始时间 + 时长，未填时长时为 null）")
    private LocalDateTime endAt;

    @Schema(description = "预计时长（分钟），可空")
    private Integer durationMinutes;

    @Schema(description = "形式：ONSITE / ONLINE / PHONE，可空")
    private String eventMode;

    @Schema(description = "地点或会议链接，可空")
    private String location;

    @Schema(description = "备注，可空")
    private String note;

    @Schema(description = "状态：PENDING 待进行 / DONE 已完成 / CANCELLED 已取消")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;
}
