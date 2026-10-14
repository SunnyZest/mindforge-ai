package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * ATS 关键词跑分结果。
 * <p>
 * ⚠️ <b>和「匹配分析」（{@code ResumeMatchVO}）是两回事，前端别混用</b>：
 * <ul>
 *   <li>本结果 = <b>机器筛简历的视角</b>：JD 里的关键词在简历里出现了没有。
 *       纯本地计算（不调模型）、毫秒级、同样的输入永远同样的分。</li>
 *   <li>匹配分析 = <b>人的视角</b>：能力够不够、表达有没有缺口（五类结论），
 *       要调模型、几十秒。</li>
 * </ul>
 * 关键词覆盖率低 ≠ 能力不行（可能只是没写），反过来覆盖率 100% 也不代表能过面试。
 * 所以 {@link #disclaimer} 会原样带上这句话给用户看。
 *
 * @author crx
 * @since 2026-10-14
 */
@Schema(description = "ATS 关键词跑分结果")
@Data
public class AtsScoreVO {

    @Schema(description = "简历 id")
    private String resumeId;

    @Schema(description = "职位 id")
    private String jobId;

    @Schema(description = "关键词覆盖率得分（0-100，保留一位小数）")
    private Double score;

    @Schema(description = "档位：HIGH（≥80）/ MEDIUM（≥60）/ LOW，前端用它决定配色")
    private String level;

    @Schema(description = "JD 关键词总数")
    private Integer totalKeywords;

    @Schema(description = "命中的关键词数")
    private Integer matchedKeywords;

    @Schema(description = "必须项总数（job_skill.required = 1）")
    private Integer requiredTotal;

    @Schema(description = "命中的必须项数")
    private Integer requiredMatched;

    @Schema(description = "一句话结论")
    private String summary;

    @Schema(description = "全部关键词及命中情况（前端展示成带勾/叉的标签墙）")
    private List<KeywordVO> keywords;

    @Schema(description = "缺失的必须项 —— 最该先处理的，前端标红")
    private List<KeywordVO> missingRequired;

    @Schema(description = "改进建议（能直接照着做的一句话）")
    private List<String> advice;

    @Schema(description = "固定免责说明：本分数只反映关键词是否出现，不代表能力匹配度")
    private String disclaimer;

    @Schema(description = "关键词明细")
    @Data
    public static class KeywordVO {

        @Schema(description = "关键词，如 分库分表")
        private String keyword;

        @Schema(description = "技能类型，如 LANGUAGE / FRAMEWORK，可空")
        private String skillType;

        @Schema(description = "是否 JD 明确要求的必须项")
        private Boolean required;

        @Schema(description = "简历里是否命中")
        private Boolean hit;

        @Schema(description = "命中来源：SKILL = 简历技能表里有；TEXT = 只在简历原文里出现；未命中为 null")
        private String hitSource;

        @Schema(description = "简历里标注的熟练度（结构化命中时才有），如 精通")
        private String proficiency;

        @Schema(description = "在简历原文里出现的次数（0 = 没出现）")
        private Integer occurrences;
    }
}
