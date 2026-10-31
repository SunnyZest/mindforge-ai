package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试复盘报告，文档 §二十二 {@code GET /api/app/interviews/{id}/report}。
 * <p>
 * 结构直接对齐 {@code interview_report} 表。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "面试复盘报告")
@Data
public class InterviewReportVO {

    @Schema(description = "报告 id")
    private String id;

    @Schema(description = "所属面试 id")
    private String interviewId;

    @Schema(description = "综合得分（0~100）")
    private Double overallScore;

    @Schema(description = "技术能力得分（0~100）")
    private Double technicalScore;

    @Schema(description = "沟通表达得分（0~100）")
    private Double communicationScore;

    @Schema(description = "问题解决得分（0~100）")
    private Double problemSolvingScore;

    /**
     * 工程规范得分（0~100）。
     * <p>
     * ⚠️ 可空：历史报告（2026-10-30 之前生成）没有这一维，返回 null。
     * 前端按「有几个画几个」处理，不足三个维度不画雷达图。
     * <p>
     * ⚠️ 为 null 时字段<b>仍然出现在 JSON 里</b>（项目未配 NON_NULL），
     * 前端要判 {@code value != null}，不能用「key 是否存在」判断。
     */
    @Schema(description = "工程规范得分（0~100），历史报告为 null")
    private Double engineeringScore;

    /** 岗位契合得分（0~100）。没指定目标岗位时为 null —— 前端不要拿别的分数凑 */
    @Schema(description = "岗位契合得分（0~100），未指定目标岗位时为 null")
    private Double jobFitScore;

    @Schema(description = "优势")
    private List<String> strengths;

    @Schema(description = "不足")
    private List<String> weaknesses;

    @Schema(description = "改进建议")
    private List<String> suggestions;

    @Schema(description = "完整报告（Markdown）")
    private String reportText;

    @Schema(description = "生成时间")
    private LocalDateTime createdAt;
}
