package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情，文档 §二十二 {@code GET /api/app/interviews/{id}}。
 * <p>
 * 比 {@link InterviewVO} 多一个 {@code questions} —— 答题页要的就是它。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "面试详情")
@Data
public class InterviewDetailVO {

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

    @Schema(description = "题目总数")
    private Integer questionCount;

    @Schema(description = "已作答的题数")
    private Integer answeredCount;

    @Schema(description = "关联简历 id，可空")
    private String resumeId;

    @Schema(description = "关联职位 id，可空")
    private String jobId;

    @Schema(description = "开始作答时间")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 按题号升序。没出题时是空数组，不是 null */
    @Schema(description = "题目列表（按题号升序）")
    private List<InterviewQuestionVO> questions;
}
