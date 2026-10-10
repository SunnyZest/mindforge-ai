package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.client.AiServiceClient.ResumeMatchData;
import com.zest.mindforgeai.client.AiServiceClient.ResumeMatchRequest;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.ResumeJobAnalysisMapper;
import com.zest.mindforgeai.pojo.dto.ResumeMatchDTO;
import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeMatchVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.JobService;
import com.zest.mindforgeai.service.ResumeJobAnalysisService;
import com.zest.mindforgeai.service.ResumeService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 简历-JD 匹配分析实现
 *
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Service
public class ResumeJobAnalysisServiceImpl
        extends ServiceImpl<ResumeJobAnalysisMapper, ResumeJobAnalysis>
        implements ResumeJobAnalysisService {

    private final ResumeService resumeService;
    private final JobService jobService;
    private final AiServiceClient aiClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ResumeJobAnalysisServiceImpl(ResumeService resumeService,
                                        JobService jobService,
                                        AiServiceClient aiClient,
                                        AuditService auditService,
                                        ObjectMapper objectMapper,
                                        TransactionTemplate transactionTemplate) {
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.aiClient = aiClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * ⚠️ 刻意<b>不加 {@code @Transactional}</b> —— 整段要调模型（几十秒到两分钟），
     * 包在事务里会一直占着数据库连接（同 {@code ResumeServiceImpl#parse} 的理由）。
     * 落库那一步单独用 {@link TransactionTemplate} 保证原子。
     */
    @Override
    public ResumeMatchVO analyze(ResumeMatchDTO dto) {
        Long resumeId = parseId(dto.getResumeId(), "简历 id");
        Long jobId = parseId(dto.getJobId(), "职位 id");

        // ① 两边都校验归属（不是自己的 → 404，不区分「不存在」和「不是你的」）
        ResumeDetailVO resume = resumeService.getDetail(resumeId);
        JobDetailVO job = jobService.getDetail(jobId);

        // ② 匹配的输入就是这两段原文，缺一个都做不了
        if (!StringUtils.hasText(resume.getRawContent())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这份简历还没有解析过，请先解析再匹配");
        }
        if (!StringUtils.hasText(job.getRawContent())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这个职位还没有解析过，请先解析再匹配");
        }

        // ③ 调 AI —— 这一步不占数据库事务
        ResumeMatchData data = aiClient.matchResume(new ResumeMatchRequest(
                String.valueOf(SecurityUtils.getUserId()),
                String.valueOf(resumeId),
                String.valueOf(jobId),
                resume.getRawContent(),
                job.getRawContent()));

        // ④ 落库（覆盖同一对的旧结果）
        ResumeJobAnalysis saved = persist(resumeId, jobId, data);

        log.info("简历匹配完成 resumeId={} jobId={} 综合={} 逐条={} 条（{}）",
                resumeId, jobId, data.overallScore(),
                data.items() == null ? 0 : data.items().size(),
                verdictSummary(data.items()));

        auditService.recordSuccess(AuditConstants.ACTION_RESUME_MATCH,
                AuditConstants.RESOURCE_RESUME_JOB_ANALYSIS, String.valueOf(saved.getId()),
                Map.of("resumeId", String.valueOf(resumeId),
                       "jobId", String.valueOf(jobId),
                       "itemCount", data.items() == null ? 0 : data.items().size()));

        return toVO(saved);
    }

    @Override
    public ResumeMatchVO getLatest(Long resumeId, Long jobId) {
        ResumeJobAnalysis entity = getOne(new LambdaQueryWrapper<ResumeJobAnalysis>()
                .eq(ResumeJobAnalysis::getUserId, SecurityUtils.getUserId())
                .eq(ResumeJobAnalysis::getResumeId, resumeId)
                .eq(ResumeJobAnalysis::getJobId, jobId)
                .orderByDesc(ResumeJobAnalysis::getId)
                .last("LIMIT 1"));

        if (entity == null) {
            throw new BizException(ErrorCode.RESUME_JOB_ANALYSIS_NOT_FOUND);
        }
        return toVO(entity);
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 落库（一个事务）。
     * <p>
     * ★ <b>先删后插</b>：同一对（简历 + 职位）只保留一条。
     * 表上的 {@code idx_analysis_resume_job} <b>不是唯一索引</b>，
     * 所以这个约束是代码保证的 —— 直接 insert 会攒出一堆历史记录，
     * 而表里除了 {@code created_at} 没有任何字段能区分「哪条是当前的」。
     */
    private ResumeJobAnalysis persist(Long resumeId, Long jobId, ResumeMatchData data) {
        Long userId = SecurityUtils.getUserId();

        ResumeJobAnalysis entity = new ResumeJobAnalysis()
                .setUserId(userId)
                .setResumeId(resumeId)
                .setJobId(jobId)
                .setOverallScore(toDecimal(data.overallScore()))
                .setMatchedSkillsJson(toJson(data.matchedSkills()))
                .setMissingSkillsJson(toJson(data.missingSkills()))
                .setMatchedExperienceJson(toJson(data.matchedExperience()))
                .setMissingExperienceJson(toJson(data.missingExperience()))
                // ★ 核心字段：五类结论
                .setMatchItemsJson(toJson(data.items()))
                .setSuggestionsJson(toJson(data.suggestions()))
                .setAnalysisText(data.summary())
                // 用的哪个模型由 Python 侧的配置决定，Java 看不到 —— 留 null 比瞎填一个强
                .setModelName(null)
                .setCreatedAt(LocalDateTime.now());

        transactionTemplate.executeWithoutResult(tx -> {
            remove(new LambdaQueryWrapper<ResumeJobAnalysis>()
                    .eq(ResumeJobAnalysis::getUserId, userId)
                    .eq(ResumeJobAnalysis::getResumeId, resumeId)
                    .eq(ResumeJobAnalysis::getJobId, jobId));
            save(entity);
        });

        return entity;
    }

    /** 实体 → VO。JSON 列在这里解析回结构，所以前端拿到的就是库里存的那份 */
    private ResumeMatchVO toVO(ResumeJobAnalysis entity) {
        ResumeMatchVO vo = new ResumeMatchVO();
        vo.setAnalysisId(String.valueOf(entity.getId()));
        vo.setResumeId(String.valueOf(entity.getResumeId()));
        vo.setJobId(String.valueOf(entity.getJobId()));
        vo.setOverallScore(toDouble(entity.getOverallScore()));
        vo.setSummary(entity.getAnalysisText());
        vo.setItems(parseMatchItems(entity.getMatchItemsJson()));
        vo.setMatchedSkills(parseStringList(entity.getMatchedSkillsJson()));
        vo.setMissingSkills(parseStringList(entity.getMissingSkillsJson()));
        vo.setMatchedExperience(parseStringList(entity.getMatchedExperienceJson()));
        vo.setMissingExperience(parseStringList(entity.getMissingExperienceJson()));
        vo.setSuggestions(parseStringList(entity.getSuggestionsJson()));
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** 日志用：五类各几条，一眼看出这次结论的分布 */
    private String verdictSummary(List<ResumeMatchData.MatchItem> items) {
        if (items == null || items.isEmpty()) {
            return "无";
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ResumeMatchData.MatchItem item : items) {
            counts.merge(StringUtils.hasText(item.verdict()) ? item.verdict() : "UNKNOWN",
                    1, Integer::sum);
        }
        return counts.toString();
    }

    private List<ResumeMatchVO.MatchItemVO> parseMatchItems(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<ResumeMatchVO.MatchItemVO> items = objectMapper.readValue(
                    json, new TypeReference<>() {
                    });
            return items == null ? List.of() : items;
        } catch (Exception e) {
            log.warn("匹配结论不是合法 JSON，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("JSON 数组解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    /** 对象 → JSON 字符串。序列化失败返回 null（存不了比存个错的强） */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败，按空处理：{}", e.getMessage());
            return null;
        }
    }

    /**
     * Double → BigDecimal。
     * <p>
     * 用 {@code valueOf} 而不是 {@code new BigDecimal(double)} ——
     * 后者会把 66.5 变成 66.5000000000000045474735088646411895751953125。
     * 列是 decimal(6,2)，写进去再读出来就不是原来那个数了。
     */
    private BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** 前端传的 id 是字符串（避免 JS 精度丢失），格式不对按参数错误报而不是 500 */
    private Long parseId(String value, String field) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "格式不正确：" + value);
        }
    }
}
