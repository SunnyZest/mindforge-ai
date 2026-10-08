package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.JobMapper;
import com.zest.mindforgeai.pojo.dto.JobCreateDTO;
import com.zest.mindforgeai.pojo.dto.JobUpdateDTO;
import com.zest.mindforgeai.pojo.entity.Job;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.JobVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.JobService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职位 JD 服务实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class JobServiceImpl extends ServiceImpl<JobMapper, Job> implements JobService {

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    private static final String PENDING = "PENDING";

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public JobServiceImpl(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResultVO<JobVO> pageJobs(long page, long pageSize, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<Job>()
                .eq(Job::getUserId, SecurityUtils.getUserId())
                // 关键词：职位名称 / 公司名称 任一匹配
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Job::getTitle, keyword).or()
                        .like(Job::getCompanyName, keyword))
                .orderByDesc(Job::getId);

        Page<Job> result = page(new Page<>(safePage, safeSize), wrapper);
        return PageResultVO.of(result, result.getRecords().stream().map(JobVO::of).toList());
    }

    @Override
    public JobDetailVO getDetail(Long id) {
        Job job = requireOwned(id);
        return JobDetailVO.of(job, parseStructuredJson(job.getStructuredJson()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobVO create(JobCreateDTO dto) {
        Job job = new Job()
                .setUserId(SecurityUtils.getUserId())
                .setTitle(dto.getTitle())
                .setCompanyName(dto.getCompanyName())
                .setLocation(dto.getLocation())
                .setSourceUrl(dto.getSourceUrl())
                .setExperienceRequired(dto.getExperienceRequired())
                .setEducationRequired(dto.getEducationRequired())
                // 原始描述存进 raw_content —— 这是 AI 解析的输入
                .setRawContent(dto.getJobDescription())
                .setParseStatus(PENDING)
                .setStatus(1);
        save(job);

        log.info("创建职位 JD id={} title={}", job.getId(), job.getTitle());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("title", job.getTitle());
        detail.put("companyName", job.getCompanyName());
        auditService.recordSuccess(AuditConstants.ACTION_JOB_CREATE,
                AuditConstants.RESOURCE_JOB, String.valueOf(job.getId()), detail);

        return JobVO.of(job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobVO update(Long id, JobUpdateDTO dto) {
        Job job = requireOwned(id);

        // ★ JD 正文变了，之前的解析结果就失效了。
        //   所以把 parse_status 打回 PENDING、并清掉 structured_json。
        //   为什么清而不是留着：structured_json 是【派生数据】，
        //   源文本一变它就是错的。留着的话，只要有人忘了看 parse_status
        //   就会读到一份描述着旧 JD 的结构化数据 —— 这比丢掉它危险得多。
        //   清掉之后有个清晰的不变式：structured_json 非空 ⟺ parse_status = SUCCESS。
        boolean contentChanged = dto.getJobDescription() != null
                && !dto.getJobDescription().equals(job.getRawContent());

        // ⚠️ 用 LambdaUpdateWrapper 而不是 updateById：
        //    updateById 默认忽略 null 字段，传 null 等于「不修改」，
        //    没法把 structured_json 清成 NULL。
        LambdaUpdateWrapper<Job> wrapper = new LambdaUpdateWrapper<Job>()
                .eq(Job::getId, id)
                .set(dto.getTitle() != null, Job::getTitle, dto.getTitle())
                .set(dto.getCompanyName() != null, Job::getCompanyName, dto.getCompanyName())
                .set(dto.getLocation() != null, Job::getLocation, dto.getLocation())
                .set(dto.getSourceUrl() != null, Job::getSourceUrl, dto.getSourceUrl())
                .set(dto.getExperienceRequired() != null,
                        Job::getExperienceRequired, dto.getExperienceRequired())
                .set(dto.getEducationRequired() != null,
                        Job::getEducationRequired, dto.getEducationRequired())
                .set(dto.getJobDescription() != null, Job::getRawContent, dto.getJobDescription())
                .set(dto.getStatus() != null, Job::getStatus, dto.getStatus())
                .set(Job::getUpdatedAt, LocalDateTime.now());

        if (contentChanged) {
            wrapper.set(Job::getParseStatus, PENDING)
                    .set(Job::getStructuredJson, null);
        }

        update(wrapper);

        log.info("修改职位 JD id={} 正文变更={}", id, contentChanged);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("beforeTitle", job.getTitle());
        detail.put("afterTitle", dto.getTitle());
        detail.put("contentChanged", contentChanged);
        auditService.recordSuccess(AuditConstants.ACTION_JOB_UPDATE,
                AuditConstants.RESOURCE_JOB, String.valueOf(id), detail);

        return JobVO.of(getById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Job job = requireOwned(id);
        removeById(id);

        log.info("删除职位 JD id={} title={}", id, job.getTitle());
        auditService.recordSuccess(AuditConstants.ACTION_JOB_DELETE,
                AuditConstants.RESOURCE_JOB, String.valueOf(id),
                Map.of("title", job.getTitle()));
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 取职位并校验归属。
     * <p>
     * 不存在、或者存在但不属于当前用户，都抛 {@code JOB_NOT_FOUND} ——
     * <b>刻意不区分</b>：抛 403 等于告诉调用方「这个 id 是存在的，只是不归你」，
     * 相当于帮他确认了别人的数据范围。抛 404 什么都不泄漏。
     */
    private Job requireOwned(Long id) {
        Job job = getById(id);
        if (job == null || !job.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.JOB_NOT_FOUND);
        }
        return job;
    }

    /** JSON 列 → JsonNode。内容损坏时返回 null 而不是让整个请求 500 */
    private JsonNode parseStructuredJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("结构化数据不是合法 JSON，按未解析处理：{}", e.getMessage());
            return null;
        }
    }
}
