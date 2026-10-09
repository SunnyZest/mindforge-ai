package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.client.AiServiceClient.JobParseData;
import com.zest.mindforgeai.client.AiServiceClient.JobParseRequest;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.JobMapper;
import com.zest.mindforgeai.mapper.JobSkillMapper;
import com.zest.mindforgeai.pojo.dto.JobCreateDTO;
import com.zest.mindforgeai.pojo.dto.JobUpdateDTO;
import com.zest.mindforgeai.pojo.entity.Job;
import com.zest.mindforgeai.pojo.entity.JobSkill;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.JobVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.JobService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final String PARSING = "PARSING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";

    /** 与 job.error_message varchar(500) 对齐 */
    private static final int MAX_ERROR_LEN = 500;

    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AiServiceClient aiServiceClient;
    private final JobSkillMapper jobSkillMapper;
    private final TransactionTemplate transactionTemplate;

    public JobServiceImpl(AuditService auditService,
                          ObjectMapper objectMapper,
                          AiServiceClient aiServiceClient,
                          JobSkillMapper jobSkillMapper,
                          TransactionTemplate transactionTemplate) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.aiServiceClient = aiServiceClient;
        this.jobSkillMapper = jobSkillMapper;
        this.transactionTemplate = transactionTemplate;
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

    @Override
    public JobDetailVO parse(Long id) {
        Job job = requireOwned(id);

        if (!StringUtils.hasText(job.getRawContent())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这个职位没有 JD 正文，无法解析");
        }
        // 并发保护：解析要等模型生成，这期间再点一次会起第二个调用 ——
        // 两次结果互相覆盖，还白烧一遍 token。
        if (PARSING.equals(job.getParseStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "职位正在解析中，请稍候");
        }

        // ★ 先标 PARSING 并【独立提交】，理由同简历解析：
        //   共用一个事务的话，这个中间态对外永远不可见，前端轮询只会看到 PENDING。
        markParseStatus(id, PARSING, null);

        try {
            JobParseData data = aiServiceClient.parseJob(new JobParseRequest(
                    String.valueOf(SecurityUtils.getUserId()),
                    String.valueOf(id),
                    job.getRawContent()));

            persistParsed(id, data);

            log.info("JD 解析成功 jobId={} 技能={} 项", id,
                    data.skills() == null ? 0 : data.skills().size());

            auditService.recordSuccess(AuditConstants.ACTION_JOB_PARSE,
                    AuditConstants.RESOURCE_JOB, String.valueOf(id),
                    Map.of("skills", data.skills() == null ? 0 : data.skills().size(),
                           "truncated", Boolean.TRUE.equals(data.truncated())));

        } catch (RuntimeException e) {
            // 失败留痕：原因写进 error_message，前端才能说出「为什么失败」。
            // 写完照常抛出 —— 这次请求本身就是失败的。
            //
            // ⚠️ 刻意【不动】structured_json / job_skill：正文没变，
            //    上一次成功的结果依然是对的，不该因为一次重试失败就删掉。
            markParseStatus(id, FAILED, truncateError(e.getMessage()));
            log.error("JD 解析失败 jobId={}", id, e);
            throw e;
        }

        return getDetail(id);
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

    /**
     * 把解析结果落库（一个事务）。
     * <p>
     * 为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}，
     * 见 {@code ResumeServiceImpl#persistParsed} 的说明 —— 同一套理由：
     * AI 调用不能包在事务里（会占着连接几十秒），但落库这几步必须原子。
     */
    private void persistParsed(Long id, JobParseData data) {
        JsonNode structured = data.structured();
        String structuredJson = structured == null ? null : structured.toString();

        // 经验和学历要求是【派生数据】，以 AI 从 JD 正文里读出来的为准 ——
        // 建职位时用户可能没填、或者随手写了个大概。
        // 正文里没写（AI 返回 null）就【保持原值不动】，不要覆盖成 null 把用户填的抹掉。
        String experience = textOrNull(structured, "experienceRequired");
        String education = textOrNull(structured, "educationRequired");

        transactionTemplate.executeWithoutResult(tx -> {
            // ⚠️ 用 LambdaUpdateWrapper 而不是 updateById：
            //    updateById 默认忽略 null 字段，清不掉上一次失败留下的 error_message
            update(new LambdaUpdateWrapper<Job>()
                    .eq(Job::getId, id)
                    .set(Job::getStructuredJson, structuredJson)
                    .set(experience != null, Job::getExperienceRequired, experience)
                    .set(education != null, Job::getEducationRequired, education)
                    .set(Job::getParseStatus, SUCCESS)
                    .set(Job::getErrorMessage, null)
                    .set(Job::getUpdatedAt, LocalDateTime.now()));

            // 技能：全删全插（job_skill 没有 deleted 列，物理删）。
            // 重新解析时必须清掉旧的 —— 否则「上次识别出、这次没有」的要求会一直留着，
            // 简历匹配时会被当成真实缺口。
            jobSkillMapper.delete(new LambdaQueryWrapper<JobSkill>()
                    .eq(JobSkill::getJobId, id));

            for (JobParseData.Skill skill : nullToEmpty(data.skills())) {
                if (!StringUtils.hasText(skill.name())) {
                    continue;
                }
                jobSkillMapper.insert(new JobSkill()
                        .setJobId(id)
                        .setSkillName(skill.name())
                        .setSkillType(skill.type())
                        .setRequiredLevel(skill.requiredLevel())
                        // 列是 tinyint，Python 给的是布尔。
                        // null 按「加分项」处理 —— 和 Python 侧 _normalize_skills 的默认值一致
                        .setRequired(Boolean.TRUE.equals(skill.required()) ? 1 : 0));
            }
        });
    }

    /**
     * 只改解析状态（和失败原因）。
     * <p>
     * ⚠️ 刻意不加 {@code @Transactional}：{@code PARSING} 是给前端轮询看的中间态，
     * 包进事务就等于没写。
     */
    private void markParseStatus(Long id, String status, String errorMessage) {
        update(new LambdaUpdateWrapper<Job>()
                .eq(Job::getId, id)
                .set(Job::getParseStatus, status)
                .set(Job::getErrorMessage, errorMessage)
                .set(Job::getUpdatedAt, LocalDateTime.now()));
    }

    /** 失败原因截断到列宽。完整信息在服务日志里 */
    private String truncateError(String message) {
        if (!StringUtils.hasText(message)) {
            return "解析失败（上游未提供原因）";
        }
        return message.length() > MAX_ERROR_LEN ? message.substring(0, MAX_ERROR_LEN) : message;
    }

    /** 从结构化数据里取一个字符串字段；字段不存在或是 null 都返回 null */
    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
