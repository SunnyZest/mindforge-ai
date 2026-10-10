package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 提交单题回答的结果，文档 §二十二 {@code POST /api/app/interviews/{id}/answers}。
 * <p>
 * 设计要点：<b>一次请求同时给出「这题怎么样」和「下一题是什么」</b>。
 * 前端提交答案后不用再发一次请求拿下一题 —— 少一次往返，
 * 而且答题页的状态转移只有一个来源，不会出现「分析到了、下一题还没到」的中间态。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "提交回答的结果")
@Data
public class InterviewAnswerResultVO {

    @Schema(description = "刚作答的题目 id")
    private String questionId;

    @Schema(description = "刚作答的题号")
    private Integer questionNo;

    @Schema(description = "本题得分（0~100）")
    private Double score;

    @Schema(description = "本题的分析")
    private InterviewAnalysisVO analysis;

    /**
     * 下一道题，没有就是 null。
     * <p>
     * 取的是<b>题号最小的、还没作答的题</b> —— 不是「当前题号 +1」。
     * 这样用户跳着答（先答第 3 题）也不会把前面的题漏掉。
     */
    @Schema(description = "下一道未作答的题；全部答完时为 null")
    private NextQuestionVO nextQuestion;

    @Schema(description = "已作答题数")
    private Integer answeredCount;

    @Schema(description = "题目总数")
    private Integer totalCount;

    @Schema(description = "是否全部答完（前端据此显示「生成报告」按钮）")
    private Boolean allAnswered;

    @Schema(description = "下一道题")
    @Data
    public static class NextQuestionVO {

        @Schema(description = "题目 id")
        private String id;

        @Schema(description = "题号")
        private Integer questionNo;

        @Schema(description = "题目内容")
        private String question;

        @Schema(description = "题型")
        private String questionType;

        @Schema(description = "难度")
        private String difficulty;
    }
}
