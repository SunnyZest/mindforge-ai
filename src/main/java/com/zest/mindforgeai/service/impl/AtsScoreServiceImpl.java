package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.JobSkillMapper;
import com.zest.mindforgeai.mapper.ResumeSkillMapper;
import com.zest.mindforgeai.pojo.entity.JobSkill;
import com.zest.mindforgeai.pojo.entity.ResumeSkill;
import com.zest.mindforgeai.pojo.vo.AtsScoreVO;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.service.AtsScoreService;
import com.zest.mindforgeai.service.JobService;
import com.zest.mindforgeai.service.ResumeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ATS 关键词跑分实现。
 *
 * @author crx
 * @since 2026-10-14
 */
@Slf4j
@Service
public class AtsScoreServiceImpl implements AtsScoreService {

    /** 必须项权重：真实 ATS 也是「硬性要求没命中直接扣死」，所以给 2 倍 */
    private static final double WEIGHT_REQUIRED = 2.0;
    /** 加分项权重 */
    private static final double WEIGHT_OPTIONAL = 1.0;

    /** 分数档位阈值 */
    private static final double LEVEL_HIGH = 80.0;
    private static final double LEVEL_MEDIUM = 60.0;

    private static final String SOURCE_SKILL = "SKILL";
    private static final String SOURCE_TEXT = "TEXT";

    private static final String DISCLAIMER =
            "本分数只反映「JD 里的关键词在简历里出现了没有」，是机器筛简历的视角 —— "
                    + "覆盖率高不代表能力匹配，低了也可能只是没写清楚。"
                    + "能力层面的判断请看「简历-JD 匹配分析」。";

    /**
     * 真别名表：规范化后写法不同、但指的是同一个东西。
     * <p>
     * ⚠️ 只收「业界公认的等价缩写」，不要往里塞近义词 ——
     * 把 MySQL 和 PostgreSQL 当等价会让分数虚高，用户拿着虚高的分去投简历是害他。
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("k8s", "kubernetes"),
            Map.entry("es", "elasticsearch"),
            Map.entry("pg", "postgresql"),
            Map.entry("postgres", "postgresql"),
            Map.entry("js", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("golang", "go"));

    private final ResumeService resumeService;
    private final JobService jobService;
    private final JobSkillMapper jobSkillMapper;
    private final ResumeSkillMapper resumeSkillMapper;

    public AtsScoreServiceImpl(ResumeService resumeService,
                               JobService jobService,
                               JobSkillMapper jobSkillMapper,
                               ResumeSkillMapper resumeSkillMapper) {
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.jobSkillMapper = jobSkillMapper;
        this.resumeSkillMapper = resumeSkillMapper;
    }

    @Override
    public AtsScoreVO score(Long resumeId, Long jobId) {
        // ① 校验两边归属（不是自己的 → 404，不区分「不存在」和「不是你的」）
        ResumeDetailVO resume = resumeService.getDetail(resumeId);
        JobDetailVO job = jobService.getDetail(jobId);

        // ② JD 的技能项是打分的输入 —— 它由 JD 解析产生
        List<JobSkill> jobSkills = jobSkillMapper.selectList(
                new LambdaQueryWrapper<JobSkill>().eq(JobSkill::getJobId, jobId));
        if (jobSkills.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "这个职位还没有解析出技能项，请先解析 JD 再跑分"
                            + "（若已解析但仍为空，说明该 JD 里没有可识别的技术关键词）");
        }

        // ③ 简历侧的两个证据源：结构化技能表（准）+ 原文（全）
        List<ResumeSkill> resumeSkills = resumeSkillMapper.selectList(
                new LambdaQueryWrapper<ResumeSkill>().eq(ResumeSkill::getResumeId, resumeId));
        String resumeText = resume.getRawContent() == null ? "" : resume.getRawContent();

        // 规范化后的技能名 → 熟练度，用于结构化命中判定
        Map<String, String> resumeSkillIndex = new HashMap<>();
        for (ResumeSkill skill : resumeSkills) {
            if (StringUtils.hasText(skill.getSkillName())) {
                resumeSkillIndex.putIfAbsent(canonical(skill.getSkillName()), skill.getProficiency());
            }
        }

        // ④ 逐词判定
        List<AtsScoreVO.KeywordVO> keywords = new ArrayList<>(jobSkills.size());
        double totalWeight = 0;
        double hitWeight = 0;
        int requiredTotal = 0;
        int requiredMatched = 0;

        for (JobSkill jobSkill : jobSkills) {
            String keyword = jobSkill.getSkillName();
            if (!StringUtils.hasText(keyword)) {
                continue;
            }

            boolean required = isRequired(jobSkill);
            double weight = required ? WEIGHT_REQUIRED : WEIGHT_OPTIONAL;
            totalWeight += weight;
            if (required) {
                requiredTotal++;
            }

            int occurrences = countOccurrences(resumeText, keyword);
            String proficiency = resumeSkillIndex.get(canonical(keyword));
            boolean structuredHit = proficiency != null;
            boolean hit = structuredHit || occurrences > 0;

            if (hit) {
                hitWeight += weight;
                if (required) {
                    requiredMatched++;
                }
            }

            AtsScoreVO.KeywordVO vo = new AtsScoreVO.KeywordVO();
            vo.setKeyword(keyword);
            vo.setSkillType(jobSkill.getSkillType());
            vo.setRequired(required);
            vo.setHit(hit);
            vo.setHitSource(structuredHit ? SOURCE_SKILL : (occurrences > 0 ? SOURCE_TEXT : null));
            vo.setProficiency(proficiency);
            vo.setOccurrences(occurrences);
            keywords.add(vo);
        }

        if (totalWeight == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这个职位没有可用的技能关键词，无法跑分");
        }

        // ⑤ 加权覆盖率（必须项 2 倍权重）
        double score = Math.round(hitWeight / totalWeight * 1000.0) / 10.0;
        int matched = (int) keywords.stream().filter(k -> Boolean.TRUE.equals(k.getHit())).count();

        List<AtsScoreVO.KeywordVO> missingRequired = keywords.stream()
                .filter(k -> Boolean.TRUE.equals(k.getRequired()) && !Boolean.TRUE.equals(k.getHit()))
                .toList();

        AtsScoreVO vo = new AtsScoreVO();
        vo.setResumeId(String.valueOf(resumeId));
        vo.setJobId(String.valueOf(jobId));
        vo.setScore(score);
        vo.setLevel(levelOf(score));
        vo.setTotalKeywords(keywords.size());
        vo.setMatchedKeywords(matched);
        vo.setRequiredTotal(requiredTotal);
        vo.setRequiredMatched(requiredMatched);
        vo.setKeywords(keywords);
        vo.setMissingRequired(missingRequired);
        vo.setSummary(buildSummary(keywords.size(), matched, requiredTotal, requiredMatched, score));
        vo.setAdvice(buildAdvice(missingRequired, keywords));
        vo.setDisclaimer(DISCLAIMER);

        log.info("ATS 跑分 resumeId={} jobId={} 得分={} 命中 {}/{}（必须项 {}/{}）",
                resumeId, jobId, score, matched, keywords.size(), requiredMatched, requiredTotal);

        return vo;
    }

    /* ---------- 内部工具 ---------- */

    private boolean isRequired(JobSkill skill) {
        // required = 1 是必须项；null 按「非必须」处理 —— 宁可少算权重，
        // 不要把没标注的当成硬性要求去吓用户
        return skill.getRequired() != null && skill.getRequired() == 1;
    }

    /**
     * 规范化 + 别名归一，作为「是不是同一个东西」的比较键。
     * <p>
     * 去空格 / 连字符 / 下划线 / 点，让 {@code Spring Boot}、{@code spring-boot}、
     * {@code SpringBoot} 落到同一个键上 —— 这一步解决绝大部分写法差异，
     * 剩下的真别名交给 {@link #ALIASES}。
     */
    private String canonical(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-_.·/]+", "")
                .trim();
        return ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * 统计关键词在简历原文里出现的次数。
     * <p>
     * ⚠️ 纯 ASCII 关键词要求「词边界」—— 否则 {@code Java} 会在 {@code JavaScript}
     * 里命中、{@code Go} 会在 {@code Google} 里命中，分数直接虚高。
     * 含中文的关键词（如「分库分表」）没有字母边界的概念，直接子串匹配。
     */
    private int countOccurrences(String text, String keyword) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(keyword)) {
            return 0;
        }

        String quoted = Pattern.quote(keyword);
        String regex = isAscii(keyword)
                ? "(?<![A-Za-z0-9])" + quoted + "(?![A-Za-z0-9])"
                : quoted;

        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count >= 99) {
                break;
            }
        }
        return count;
    }

    private boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private String levelOf(double score) {
        if (score >= LEVEL_HIGH) {
            return "HIGH";
        }
        return score >= LEVEL_MEDIUM ? "MEDIUM" : "LOW";
    }

    private String buildSummary(int total, int matched, int requiredTotal, int requiredMatched, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append("JD 的 ").append(total).append(" 个关键词命中 ").append(matched)
                .append(" 个，加权覆盖率 ").append(formatScore(score)).append(" 分。");
        if (requiredTotal > 0) {
            sb.append("其中必须项 ").append(requiredMatched).append("/").append(requiredTotal);
            if (requiredMatched == requiredTotal) {
                sb.append("（全中）");
            } else {
                sb.append("，还差 ").append(requiredTotal - requiredMatched).append(" 个");
            }
            sb.append("。");
        }
        return sb.toString();
    }

    private List<String> buildAdvice(List<AtsScoreVO.KeywordVO> missingRequired,
                                     List<AtsScoreVO.KeywordVO> keywords) {
        List<String> advice = new ArrayList<>();

        if (missingRequired.isEmpty()) {
            advice.add("JD 的必须项全部覆盖了，机器初筛这一关没问题。");
        } else {
            String names = String.join("、", missingRequired.stream()
                    .map(AtsScoreVO.KeywordVO::getKeyword).toList());
            advice.add("必须项里「" + names + "」在简历中没出现 —— 如果确实做过，"
                    + "把相关项目描述补进简历（用具体场景和量级说话）；没做过就不要硬加，面试会问。");
        }

        // 只在「必须项都齐了」时才提加分项，否则会分散注意力
        if (missingRequired.isEmpty()) {
            List<String> missingOptional = keywords.stream()
                    .filter(k -> !Boolean.TRUE.equals(k.getRequired()) && !Boolean.TRUE.equals(k.getHit()))
                    .map(AtsScoreVO.KeywordVO::getKeyword)
                    .toList();
            if (!missingOptional.isEmpty()) {
                advice.add("加分项还差「" + String.join("、", missingOptional)
                        + "」，有相关经验的话补上能进一步提高初筛通过率。");
            }
        }

        // 文本命中但没有结构化技能记录的，提示补技能列表
        List<String> textOnly = keywords.stream()
                .filter(k -> SOURCE_TEXT.equals(k.getHitSource()))
                .map(AtsScoreVO.KeywordVO::getKeyword)
                .toList();
        if (!textOnly.isEmpty()) {
            advice.add("「" + String.join("、", textOnly)
                    + "」只在正文里出现过、技能列表里没有 —— 建议也加进技能栏，"
                    + "很多 ATS 只扫技能字段。");
        }

        return advice;
    }

    /** 分数保留一位小数展示（数据库不存，只在响应里） */
    private String formatScore(double score) {
        return String.format(Locale.ROOT, "%.1f", score);
    }
}
