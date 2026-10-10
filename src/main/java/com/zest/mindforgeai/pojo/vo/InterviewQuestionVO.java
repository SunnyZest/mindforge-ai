package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情里的一道题，文档 §二十二 {@code GET /api/app/interviews/{id}}。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "面试题")
@Data
public class InterviewQuestionVO {

    @Schema(description = "题目 id")
    private String id;

    @Schema(description = "题号（同一场面试内从 1 开始连续）")
    private Integer questionNo;

    @Schema(description = "题型：TECHNICAL / BEHAVIORAL / SITUATIONAL")
    private String questionType;

    @Schema(description = "题目内容")
    private String question;

    @Schema(description = "难度：EASY / MEDIUM / HARD")
    private String difficulty;

    @Schema(description = "是否已作答")
    private Boolean answered;

    @Schema(description = "用户的回答；没作答时为 null")
    private String userAnswer;

    @Schema(description = "本题得分（0~100）；没作答时为 null")
    private Double score;

    @Schema(description = "AI 对本题回答的分析；没作答时为 null")
    private InterviewAnalysisVO aiAnalysis;

    @Schema(description = "作答时间；没作答时为 null")
    private LocalDateTime answeredAt;

    /**
     * 参考答案要点。
     * <p>
     * ⚠️ <b>只有面试结束后（status = FINISHED）才返回</b>，进行中一律为 null。
     * <p>
     * 这是评分用的参照物，面试没结束时把它给出去等于把答案提前给了用户 ——
     * 那这场面试就没有意义了。结束后再给是对的：复盘时能看到「标准答案长什么样」，
     * 报告里的「漏了哪几个要点」也才对照得起来。
     */
    @Schema(description = "参考答案要点；⚠️ 仅面试结束后返回，进行中为 null")
    private List<String> expectedPoints;
}
