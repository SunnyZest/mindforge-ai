package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 面试题表
 * </p>
 * <p>
 * ⚠️ 这张表<b>没有 {@code deleted} 列</b> —— 删除只能物理删
 * （删面试时按 {@code interview_id} 清掉，同一事务）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("interview_question")
@Schema(description = "InterviewQuestion对象,面试题表")
public class InterviewQuestion implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属面试 id")
    private Long interviewId;

    /**
     * 题号，同一场面试内唯一（表上有 {@code uk_interview_question_no}）。
     * <p>
     * 由 Python 侧重排成 1..N，不信模型给的编号 —— 模型经常从 0 开始、跳号、
     * 或者几道题共用一个号。
     */
    @Schema(description = "题号（同一场面试内唯一，从 1 开始）")
    private Integer questionNo;

    @Schema(description = "题型：TECHNICAL / BEHAVIORAL / SITUATIONAL")
    private String questionType;

    @Schema(description = "题目内容")
    private String question;

    /**
     * 参考答案要点（数组）。
     * <p>
     * 库里是 JSON 列，这里按 String 接收 —— 和 {@code resume.structured_json} 一个做法，
     * 原样存、原样取，不做反序列化再序列化。
     * <p>
     * ⚠️ 它必须存下来：评分时要作为参照物带回去。没有参照物，
     * 模型对同一份回答的打分会飘（这次 70 下次 85）。
     */
    @Schema(description = "参考答案要点（JSON 数组）")
    private String expectedPoints;

    @Schema(description = "难度：EASY / MEDIUM / HARD")
    private String difficulty;

    @Schema(description = "用户的回答")
    private String userAnswer;

    /**
     * AI 对该回答的分析，存 {@code /interviews/analyze} 返回的整段 JSON。
     * <p>
     * 存整段而不是拆成列：这些字段（technical/communication/missingPoints/comment/followUp）
     * 只有生成报告时会整体用到，没有按字段查询的需求，拆开只会让表变宽。
     */
    @Schema(description = "AI 对该回答的分析（JSON）")
    private String aiAnalysis;

    @Schema(description = "本题得分（0~100）")
    private BigDecimal score;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "作答时间")
    private LocalDateTime answeredAt;
}
