package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 单题回答的分析结果。
 * <p>
 * 两个地方会用到它，所以单独抽出来：
 * <ul>
 *   <li>{@code POST /interviews/{id}/answers} 的即时返回</li>
 *   <li>{@code GET /interviews/{id}} 里每道题的 {@code aiAnalysis}</li>
 * </ul>
 * 结构对齐 {@code interview_question.ai_analysis} 里存的那段 JSON，
 * 也对齐 Python 侧 {@code interview._normalize_analysis} 的输出。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "单题回答分析")
@Data
public class InterviewAnalysisVO {

    /** 本题总分，0~100 */
    @Schema(description = "本题总分（0~100）")
    private Double score;

    @Schema(description = "技术维度")
    private Technical technical;

    @Schema(description = "表达维度")
    private Communication communication;

    @Schema(description = "参考答案里没提到的要点 —— 复盘时最有用的部分")
    private List<String> missingPoints;

    @Schema(description = "总体点评")
    private String comment;

    @Schema(description = "建议的追问（下一轮可以往这里深挖）")
    private String followUp;

    @Schema(description = "技术维度")
    @Data
    public static class Technical {

        @Schema(description = "技术得分（0~100）")
        private Double score;

        @Schema(description = "答得好的地方")
        private List<String> strengths;

        @Schema(description = "答得不到位的地方")
        private List<String> weaknesses;
    }

    @Schema(description = "表达维度")
    @Data
    public static class Communication {

        @Schema(description = "表达清晰度（0~100）")
        private Double clarity;

        @Schema(description = "表达上的问题")
        private List<String> issues;
    }
}
