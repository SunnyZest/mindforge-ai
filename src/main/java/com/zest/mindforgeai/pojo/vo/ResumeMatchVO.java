package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历-JD 匹配结果，文档 §二十一 {@code POST /api/app/resume-job-analysis}。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "简历-JD 匹配结果")
@Data
public class ResumeMatchVO {

    @Schema(description = "分析记录 id")
    private String analysisId;

    @Schema(description = "简历 id")
    private String resumeId;

    @Schema(description = "职位 id")
    private String jobId;

    /**
     * 综合匹配分（0~100）。
     * <p>
     * ⚠️ <b>仅作展示辅助，不参与任何准入判断</b>。
     * 页面上别把它做成「进度条 + 大字」，那会诱导用户只看这个数字 ——
     * 而「73%」既不告诉他差在哪，也不告诉他去改什么。
     * <b>真正该突出的是 {@code items} 里的五类结论。</b>
     */
    @Schema(description = "综合匹配分（0~100）；⚠️ 仅展示辅助，不要当主要结论")
    private Double overallScore;

    @Schema(description = "整体判断（整体结论 + 五类统计 + 最该先做的事）")
    private String summary;

    /** ★ 权威数据。前端按 verdict 分五组渲染的就是它 */
    @Schema(description = "逐条要求的匹配结论（★ 核心字段）")
    private List<MatchItemVO> items;

    @Schema(description = "匹配上的技能名（摘要）")
    private List<String> matchedSkills;

    @Schema(description = "真实缺失的技能名（摘要）")
    private List<String> missingSkills;

    @Schema(description = "匹配上的经历（摘要）")
    private List<String> matchedExperience;

    @Schema(description = "真实缺失的经历（摘要）")
    private List<String> missingExperience;

    @Schema(description = "改进建议")
    private List<String> suggestions;

    @Schema(description = "分析时间")
    private LocalDateTime createdAt;

    /**
     * 单条要求的匹配结论。
     * <p>
     * ★ <b>{@link #verdict} 是这份结果的核心</b> —— 每条要求落到五类之一，
     * 每一类都直接指向一个动作：
     * <pre>
     * MATCHED                已匹配        简历里明确写了，能看出水平        → 不用动
     * EXPRESSION_GAP         表达缺口      能力有但没写清                    → ★ 改一句表述就行
     * INSUFFICIENT_EVIDENCE  证据不足      提了但没有可验证的细节            → 补细节
     * REAL_GAP               真实缺口      简历里完全没有                    → 得真去补
     * UNCERTAIN              待确认        从简历判断不出来                  → 问用户
     * </pre>
     * ⚠️ <b>{@code verdict = MATCHED} 时 {@link #evidence} 一定非空</b> ——
     * 后端保证的不变量：没有简历原文依据就不许判「已匹配」（会降级成 UNCERTAIN）。
     * 所以前端可以放心地拿 {@code evidence} 当「凭什么说匹配」的展示内容。
     * <p>
     * ⚠️ 页面上<b>五类要分开展示</b>（分组的清单或 tab），别混成一个大列表 ——
     * 混在一起用户就分不清「哪些改改简历就行、哪些真得去学」，
     * 而这正是这个功能唯一的价值。
     */
    @Schema(description = "单条要求的匹配结论")
    @Data
    public static class MatchItemVO {

        @Schema(description = "要求属于哪一类",
                allowableValues = {"SKILL", "EXPERIENCE", "EDUCATION", "OTHER"})
        private String category;

        @Schema(description = "岗位要求里的一条（可能被概括过）")
        private String requirement;

        @Schema(description = "★ 五类结论",
                allowableValues = {"MATCHED", "EXPRESSION_GAP", "INSUFFICIENT_EVIDENCE",
                        "REAL_GAP", "UNCERTAIN"})
        private String verdict;

        @Schema(description = "简历里的依据（照抄原文）；判断不出依据时为 null")
        private String evidence;

        @Schema(description = "针对这条结论该做什么")
        private String advice;
    }
}
