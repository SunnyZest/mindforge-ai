package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试列表项，文档 §二十二 {@code GET /api/app/interviews}。
 * <p>
 * 刻意<b>不带题目内容</b> —— 列表只用来展示「有哪些面试、进行到哪了」，
 * 把题目带上会让响应体大出十几倍。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "面试列表项")
@Data
public class InterviewVO {

    @Schema(description = "面试 id")
    private String id;

    @Schema(description = "面试标题")
    private String title;

    @Schema(description = "面试类型：TECHNICAL / BEHAVIORAL / MIXED")
    private String interviewType;

    @Schema(description = "难度：EASY / MEDIUM / HARD")
    private String difficulty;

    @Schema(description = "状态：CREATED / IN_PROGRESS / FINISHED")
    private String status;

    @Schema(description = "题目总数。0 表示还没出题（或出题失败）")
    private Integer questionCount;

    @Schema(description = "已作答的题数")
    private Integer answeredCount;

    @Schema(description = "已作答题目的平均分（0~100）；一道都没答时为 null")
    private Double averageScore;

    @Schema(description = "关联简历 id，可空")
    private String resumeId;

    @Schema(description = "关联职位 id，可空")
    private String jobId;

    @Schema(description = "开始作答时间；没开始时为 null")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间；没结束时为 null")
    private LocalDateTime finishedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
