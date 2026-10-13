package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试错题本与薄弱点复习项。
 * <p>
 * 仅归集已结束面试（{@code status = FINISHED}）中已作答的题目，
 * 此时参考答案要点（{@code expectedPoints}）与 AI 逐题诊断（{@code aiAnalysis}）均可安全展示。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "面试错题与薄弱点项")
@Data
public class InterviewMistakeVO {

    @Schema(description = "题目 id")
    private String questionId;

    @Schema(description = "所属面试 id")
    private String interviewId;

    @Schema(description = "所属面试标题")
    private String interviewTitle;

    @Schema(description = "所属面试类型：TECHNICAL / BEHAVIORAL / MIXED")
    private String interviewType;

    @Schema(description = "题号")
    private Integer questionNo;

    @Schema(description = "题型：TECHNICAL / BEHAVIORAL / SITUATIONAL")
    private String questionType;

    @Schema(description = "难度：EASY / MEDIUM / HARD")
    private String difficulty;

    @Schema(description = "题目内容")
    private String question;

    @Schema(description = "用户最近一次回答")
    private String userAnswer;

    @Schema(description = "最近一次得分（0~100）")
    private Double score;

    @Schema(description = "是否已攻克掌握（score >= 75）")
    private Boolean mastered;

    @Schema(description = "AI 对该回答的分析（含 missingPoints 漏答要点与 followUp 追问）")
    private InterviewAnalysisVO aiAnalysis;

    @Schema(description = "标准参考答案要点")
    private List<String> expectedPoints;

    @Schema(description = "最近作答/重练时间")
    private LocalDateTime answeredAt;
}
