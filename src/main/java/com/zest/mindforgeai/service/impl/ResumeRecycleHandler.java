package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.mapper.ResumeJobAnalysisMapper;
import com.zest.mindforgeai.mapper.ResumeMapper;
import com.zest.mindforgeai.mapper.ResumeSkillMapper;
import com.zest.mindforgeai.pojo.dto.ResumeRecyclePayload;
import com.zest.mindforgeai.pojo.entity.Resume;
import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import com.zest.mindforgeai.pojo.entity.ResumeSkill;
import com.zest.mindforgeai.service.RecycleRestoreHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 简历的回收站恢复策略。
 *
 * <p>删简历时：{@code resume} 逻辑删、{@code sys_file} 软删，
 * {@code resume_skill} / {@code resume_job_analysis} **物理删** —— 后者要快照重建。
 *
 * <p>★ <b>不需要重新解析</b>：正文与解析结果在 {@code resume.structured_json} 里，
 * 逻辑删不会碰它。恢复只是把技能和分析插回去。
 *
 * @author crx
 * @since 2026-10-26
 */
@Slf4j
@Component
public class ResumeRecycleHandler implements RecycleRestoreHandler {

    private final ResumeSkillMapper resumeSkillMapper;
    private final ResumeJobAnalysisMapper resumeJobAnalysisMapper;
    private final ResumeMapper resumeMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public ResumeRecycleHandler(ResumeSkillMapper resumeSkillMapper,
                                ResumeJobAnalysisMapper resumeJobAnalysisMapper,
                                ResumeMapper resumeMapper,
                                ObjectMapper objectMapper,
                                JdbcTemplate jdbc) {
        this.resumeSkillMapper = resumeSkillMapper;
        this.resumeJobAnalysisMapper = resumeJobAnalysisMapper;
        this.resumeMapper = resumeMapper;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public RecycleResourceType type() {
        return RecycleResourceType.RESUME;
    }

    @Override
    public String capturePayload(Long resumeId) {
        // 此刻 resume 还没被删（deleted = 0），能查到 fileId
        Resume resume = resumeMapper.selectById(resumeId);
        Long fileId = resume == null ? null : resume.getFileId();

        ResumeRecyclePayload payload = new ResumeRecyclePayload(
                resumeSkillMapper.selectList(new LambdaQueryWrapper<ResumeSkill>()
                        .eq(ResumeSkill::getResumeId, resumeId)),
                resumeJobAnalysisMapper.selectList(new LambdaQueryWrapper<ResumeJobAnalysis>()
                        .eq(ResumeJobAnalysis::getResumeId, resumeId)),
                fileId);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("采集简历回收站快照失败 resumeId={}，恢复后技能与匹配分析会缺失", resumeId, e);
            return null;
        }
    }

    @Override
    public boolean restoreRelated(Long resumeId, String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            log.warn("简历回收站记录没有 payload，只能恢复主表 resumeId={}（技能与匹配分析会缺失）", resumeId);
            return false;
        }

        ResumeRecyclePayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, ResumeRecyclePayload.class);
        } catch (JsonProcessingException e) {
            log.error("解析简历回收站快照失败 resumeId={}，只恢复主表", resumeId, e);
            return false;
        }

        // ⚠️ 清掉原 id 重新生成雪花，避免旧 id 被别处引用造成幽灵引用
        List<ResumeSkill> skills = payload.getSkills();
        if (skills != null) {
            for (ResumeSkill skill : skills) {
                skill.setId(null);
                resumeSkillMapper.insert(skill);
            }
        }
        List<ResumeJobAnalysis> analyses = payload.getAnalyses();
        if (analyses != null) {
            for (ResumeJobAnalysis analysis : analyses) {
                analysis.setId(null);
                resumeJobAnalysisMapper.insert(analysis);
            }
        }

        // 恢复 sys_file：必须清 deleted_at，否则 FilePurgeTask 会按保留期把 OSS 对象真删掉。
        // ⚠️ 走原生 SQL —— MP 会给 update 自动补 deleted = 0，改不到 deleted = 1 的行。
        Long fileId = payload.getFileId();
        if (fileId != null) {
            int restored = jdbc.update(
                    "UPDATE sys_file SET deleted = 0, deleted_at = NULL WHERE id = ? AND deleted = 1", fileId);
            if (restored == 0) {
                // 文件记录已被 FilePurgeTask 清掉：简历正文仍在 structured_json 里，能看不能下载。
                // 这不算「恢复不完整」—— 核心数据都回来了，只记日志。
                log.warn("简历原始文件已不可恢复（可能被 FilePurgeTask 清理）resumeId={} fileId={}",
                        resumeId, fileId);
            }
        }

        log.info("简历关联数据已重建 resumeId={} 技能={} 匹配分析={}",
                resumeId,
                skills == null ? 0 : skills.size(),
                analyses == null ? 0 : analyses.size());
        return true;
    }
}
