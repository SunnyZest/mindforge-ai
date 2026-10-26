package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.mapper.JobEventMapper;
import com.zest.mindforgeai.mapper.JobSkillMapper;
import com.zest.mindforgeai.mapper.ResumeJobAnalysisMapper;
import com.zest.mindforgeai.pojo.dto.JobRecyclePayload;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import com.zest.mindforgeai.pojo.entity.JobSkill;
import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import com.zest.mindforgeai.service.RecycleRestoreHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 职位的回收站恢复策略。
 *
 * <p>删职位时发生三件事（见 {@code JobServiceImpl.delete}）：
 * <ol>
 *   <li>{@code job} 逻辑删 —— 恢复只要改回 0，由 RecycleBinServiceImpl 统一处理；</li>
 *   <li>{@code job_skill} / {@code resume_job_analysis} <b>物理删</b> —— 必须快照重建；</li>
 *   <li>{@code job_event} 级联逻辑删 —— 记下具体 id，恢复时只还原这些。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-26
 */
@Slf4j
@Component
public class JobRecycleHandler implements RecycleRestoreHandler {

    private final JobSkillMapper jobSkillMapper;
    private final ResumeJobAnalysisMapper resumeJobAnalysisMapper;
    private final JobEventMapper jobEventMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public JobRecycleHandler(JobSkillMapper jobSkillMapper,
                             ResumeJobAnalysisMapper resumeJobAnalysisMapper,
                             JobEventMapper jobEventMapper,
                             ObjectMapper objectMapper,
                             JdbcTemplate jdbc) {
        this.jobSkillMapper = jobSkillMapper;
        this.resumeJobAnalysisMapper = resumeJobAnalysisMapper;
        this.jobEventMapper = jobEventMapper;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public RecycleResourceType type() {
        return RecycleResourceType.JOB;
    }

    @Override
    public String capturePayload(Long jobId) {
        // 三份「删了就没了」的数据：
        //   job_skill / resume_job_analysis → 物理删，必须整行快照
        //   job_event → 逻辑删，只要 id（恢复时精确还原，不按 job_id 全量捞）
        JobRecyclePayload payload = new JobRecyclePayload(
                jobSkillMapper.selectList(new LambdaQueryWrapper<JobSkill>()
                        .eq(JobSkill::getJobId, jobId)),
                resumeJobAnalysisMapper.selectList(new LambdaQueryWrapper<ResumeJobAnalysis>()
                        .eq(ResumeJobAnalysis::getJobId, jobId)),
                jobEventMapper.selectList(new LambdaQueryWrapper<JobEvent>()
                                .select(JobEvent::getId)
                                .eq(JobEvent::getJobId, jobId))
                        .stream().map(JobEvent::getId).toList());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 快照失败不阻断删除 —— 但这条记录恢复后会缺技能与分析，必须留痕
            log.error("采集职位回收站快照失败 jobId={}，恢复后技能与匹配分析会缺失", jobId, e);
            return null;
        }
    }

    @Override
    public boolean restoreRelated(Long jobId, String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            log.warn("职位回收站记录没有 payload，只能恢复主表 jobId={}（技能与匹配分析会缺失）", jobId);
            return false;
        }

        JobRecyclePayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, JobRecyclePayload.class);
        } catch (JsonProcessingException e) {
            log.error("解析职位回收站快照失败 jobId={}，只恢复主表", jobId, e);
            return false;
        }

        // ⚠️ 重建时必须清掉原 id：这些行是物理删后重新插入的，用新雪花更安全
        //    （旧 id 万一被别处引用过，复用会造出「幽灵引用」）
        if (payload.getSkills() != null) {
            for (JobSkill skill : payload.getSkills()) {
                skill.setId(null);
                jobSkillMapper.insert(skill);
            }
        }
        if (payload.getAnalyses() != null) {
            for (ResumeJobAnalysis analysis : payload.getAnalyses()) {
                analysis.setId(null);
                resumeJobAnalysisMapper.insert(analysis);
            }
        }

        // 只还原「删除时确实被一起删掉的那几条日程」。
        // ⚠️ 必须走原生 SQL：MP 会给 update 自动补 deleted = 0，永远改不到 deleted = 1 的行
        List<Long> eventIds = payload.getEventIds();
        if (eventIds != null && !eventIds.isEmpty()) {
            String placeholders = eventIds.stream().map(x -> "?").collect(Collectors.joining(","));
            int restored = jdbc.update(
                    "UPDATE job_event SET deleted = 0 WHERE deleted = 1 AND id IN (" + placeholders + ")",
                    eventIds.toArray());
            log.info("恢复职位连带日程 jobId={} 还原 {} 条", jobId, restored);
        }

        log.info("职位关联数据已重建 jobId={} 技能={} 匹配分析={}",
                jobId,
                payload.getSkills() == null ? 0 : payload.getSkills().size(),
                payload.getAnalyses() == null ? 0 : payload.getAnalyses().size());
        return true;
    }
}
