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
