package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 简历 AI 优化建议，文档 §二十一 {@code POST /api/app/resumes/{id}/optimize}。
 * <p>
 * ⚠️ <b>只是一份建议，不改简历本身</b> —— 文档明确要求「AI 优化只返回建议，
 * 不直接覆盖原始简历」。所以这个接口没有任何副作用，同样的输入可以反复调。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "简历 AI 优化建议")
@Data
public class ResumeOptimizeVO {

    @Schema(description = "简历 id")
    private String resumeId;

    @Schema(description = "整体评分（0~100），仅作展示参考 —— 真正该看的是 items")
    private Double overallScore;

    @Schema(description = "总体评价")
    private String summary;

    @Schema(description = "亮点（简历里已经写得好的地方）")
    private List<String> highlights;

    @Schema(description = "逐条建议")
    private List<OptimizeItem> items;

    /**
     * 需要用户补事实的条数。
     * <p>
     * 单独给一个计数，前端不用自己遍历 —— 直接在页头显示
     * 「有 3 处需要你补充事实」，比让用户自己数有用。
     */
    @Schema(description = "需要用户补事实的条数（items 里 needsFact = true 的个数）")
    private Integer needsFactCount;

    @Schema(description = "简历原文是否被截断（太长时只分析前面一段）")
    private Boolean truncated;

    /**
     * 单条建议。
     * <p>
     * ★ {@link #needsFact} 决定了前端该怎么展示这一条：
     * <ul>
     *   <li>{@code false} —— 「改写建议」。{@link #rewritten} 是改写后的句子，
     *       事实没变，用户可以直接拿去用</li>
     *   <li>{@code true} —— 「待补事实」。{@link #rewritten} 一定是 null，
     *       改用 {@link #missingFact} 说清要补什么（如「真实订单量、QPS 峰值」）</li>
     * </ul>
     * <b>这两类必须分开展示</b>：混在一起，用户会以为 AI 已经替他把经历写好了，
     * 而编造的经历在面试里被追问两句就露馅 —— 比不改还糟。
     */
    @Schema(description = "单条优化建议")
    @Data
    public static class OptimizeItem {

        @Schema(description = "作用的位置",
                allowableValues = {"SUMMARY", "WORK_EXPERIENCE", "PROJECT_EXPERIENCE",
                        "SKILLS", "EDUCATION", "OVERALL"})
        private String target;

        @Schema(description = "简历里的原句")
        private String original;

        @Schema(description = "问题在哪")
        private String issue;

        @Schema(description = "怎么改（说明性建议，任何情况下都有）")
        private String suggestion;

        @Schema(description = "改写后的表述；needsFact = true 时必为 null")
        private String rewritten;

        @Schema(description = "是否缺事实：true 表示要用户自己补，AI 不替他编")
        private Boolean needsFact;

        @Schema(description = "缺什么事实；needsFact = false 时为 null")
        private String missingFact;
    }
}
