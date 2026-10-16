package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.client.AiServiceClient.JobParseData;
import com.zest.mindforgeai.client.AiServiceClient.JobParseRequest;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.InterviewMapper;
import com.zest.mindforgeai.mapper.InterviewReportMapper;
import com.zest.mindforgeai.mapper.JobMapper;
import com.zest.mindforgeai.mapper.JobEventMapper;
import com.zest.mindforgeai.mapper.JobSkillMapper;
import com.zest.mindforgeai.mapper.ResumeJobAnalysisMapper;
import com.zest.mindforgeai.mapper.ResumeMapper;
import com.zest.mindforgeai.pojo.dto.JobCreateDTO;
import com.zest.mindforgeai.pojo.dto.JobCrmUpdateDTO;
import com.zest.mindforgeai.pojo.dto.JobStageUpdateDTO;
import com.zest.mindforgeai.pojo.dto.JobUpdateDTO;
import com.zest.mindforgeai.pojo.entity.Interview;
import com.zest.mindforgeai.pojo.entity.InterviewReport;
import com.zest.mindforgeai.pojo.entity.Job;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import com.zest.mindforgeai.pojo.entity.JobSkill;
import com.zest.mindforgeai.pojo.entity.Resume;
import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.JobKanbanCardVO;
import com.zest.mindforgeai.pojo.vo.JobKanbanColumnVO;
import com.zest.mindforgeai.pojo.vo.JobKanbanStatsVO;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** 看板 8 个流转阶段标准定义与显示名称 */
    public static final List<String> KANBAN_STAGES = List.of(
            "WISHLIST",
            "APPLIED",
            "SCREENING",
            "INTERVIEW_1",
            "INTERVIEW_2",
            "HR",
            "OFFER",
            "REJECTED"
    );

    public static final Map<String, String> STAGE_TITLES = Map.of(
            "WISHLIST", "心愿 / 待投递",
            "APPLIED", "已投递",
            "SCREENING", "初筛 / 笔试",
            "INTERVIEW_1", "技术一面",
            "INTERVIEW_2", "技术二面 / 终面",
            "HR", "HR 面 / 谈薪",
            "OFFER", "已获 Offer 🎉",
            "REJECTED", "未通过 / 已归档"
    );

    /** 与 job.error_message varchar(500) 对齐 */
    private static final int MAX_ERROR_LEN = 500;

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。 */
    private static final Map<String, SFunction<Job, ?>> SORTABLE = Map.of(
            "title", Job::getTitle,
            "companyName", Job::getCompanyName,
            "createdAt", Job::getCreatedAt,
            "updatedAt", Job::getUpdatedAt);

    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AiServiceClient aiServiceClient;
    private final JobSkillMapper jobSkillMapper;
    private final JobEventMapper jobEventMapper;
    private final ResumeJobAnalysisMapper resumeJobAnalysisMapper;
    private final ResumeMapper resumeMapper;
    private final InterviewMapper interviewMapper;
    private final InterviewReportMapper interviewReportMapper;
    private final TransactionTemplate transactionTemplate;

    public JobServiceImpl(AuditService auditService,
                          ObjectMapper objectMapper,
                          AiServiceClient aiServiceClient,
                          JobSkillMapper jobSkillMapper,
                          JobEventMapper jobEventMapper,
                          ResumeJobAnalysisMapper resumeJobAnalysisMapper,
                          ResumeMapper resumeMapper,
                          InterviewMapper interviewMapper,
                          InterviewReportMapper interviewReportMapper,
                          TransactionTemplate transactionTemplate) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.aiServiceClient = aiServiceClient;
        this.jobSkillMapper = jobSkillMapper;
        this.jobEventMapper = jobEventMapper;
        this.resumeJobAnalysisMapper = resumeJobAnalysisMapper;
        this.resumeMapper = resumeMapper;
        this.interviewMapper = interviewMapper;
        this.interviewReportMapper = interviewReportMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public PageResultVO<JobVO> pageJobs(long page, long pageSize, String keyword,
                                       String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        String safeKeyword = SortSupport.escapeLike(keyword);
        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<Job>()
                .eq(Job::getUserId, SecurityUtils.getUserId())
                // 关键词：职位名称 / 公司名称 任一匹配
                .and(StringUtils.hasText(safeKeyword), w -> w
                        .like(Job::getTitle, safeKeyword).or()
                        .like(Job::getCompanyName, safeKeyword));

        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, Job::getId, false);

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
                .set(StringUtils.hasText(dto.getApplicationStage()), Job::getApplicationStage, dto.getApplicationStage())
                .set(dto.getSalaryOffer() != null, Job::getSalaryOffer, dto.getSalaryOffer())
                .set(dto.getNextActionAt() != null, Job::getNextActionAt, dto.getNextActionAt())
                .set(dto.getCrmNotes() != null, Job::getCrmNotes, dto.getCrmNotes())
                .set(Job::getUpdatedAt, LocalDateTime.now());

        if (dto.getLinkedResumeId() != null) {
            Long rid = StringUtils.hasText(dto.getLinkedResumeId()) ? Long.parseLong(dto.getLinkedResumeId().trim()) : null;
            wrapper.set(Job::getLinkedResumeId, rid);
        }

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

        // 技能和匹配分析【物理删】—— 这两张表都没有 deleted 列。
        // 本项目所有表都没有外键，所以必须手动清，而且要和删除写在同一个事务里
        // （清一半会留下孤儿数据）。匹配分析引用着 jobId，职位没了它就没意义了。
        int skills = jobSkillMapper.delete(new LambdaQueryWrapper<JobSkill>()
                .eq(JobSkill::getJobId, id));
        int analyses = resumeJobAnalysisMapper.delete(new LambdaQueryWrapper<ResumeJobAnalysis>()
                .eq(ResumeJobAnalysis::getJobId, id));

        // 面试日程【逻辑删】—— job_event 有 deleted 列，走 MP 的通用删除即可。
        // 不物理删的理由：日程是用户自己排的面试安排，误删职位时还能捞回来。
        // 留下的行引用着已删职位，但所有查询都会带 deleted=0 过滤，日历里看不到它们
        // （与 document_chunk 的孤儿数据是同一套已知约定）。
        int events = jobEventMapper.delete(new LambdaQueryWrapper<JobEvent>()
                .eq(JobEvent::getJobId, id));

        log.info("删除职位 JD id={} title={}（连带清掉 {} 项技能要求、{} 条匹配分析、{} 条面试日程）",
                id, job.getTitle(), skills, analyses, events);
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

    @Override
    public List<JobKanbanColumnVO> getKanbanBoard(String keyword) {
        Long userId = SecurityUtils.getUserId();
        String safeKeyword = SortSupport.escapeLike(keyword);

        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<Job>()
                .eq(Job::getUserId, userId)
                .and(StringUtils.hasText(safeKeyword), w -> w
                        .like(Job::getTitle, safeKeyword).or()
                        .like(Job::getCompanyName, safeKeyword))
                .orderByAsc(Job::getNextActionAt)
                .orderByDesc(Job::getUpdatedAt)
                .orderByDesc(Job::getId);

        List<Job> allJobs = list(wrapper);
        if (allJobs.isEmpty()) {
            return KANBAN_STAGES.stream()
                    .map(st -> new JobKanbanColumnVO(st, STAGE_TITLES.getOrDefault(st, st), 0, List.of()))
                    .toList();
        }

        List<Long> jobIds = allJobs.stream().map(Job::getId).toList();
        Set<Long> resumeIds = allJobs.stream()
                .map(Job::getLinkedResumeId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        // 1. 批量加载关联简历名称
        Map<Long, String> resumeNameMap = new HashMap<>();
        if (!resumeIds.isEmpty()) {
            List<Resume> resumes = resumeMapper.selectList(new LambdaQueryWrapper<Resume>()
                    .select(Resume::getId, Resume::getName)
                    .in(Resume::getId, resumeIds));
            for (Resume r : resumes) {
                resumeNameMap.put(r.getId(), r.getName());
            }
        }

        // 2. 批量加载最高简历匹配得分
        Map<Long, Double> matchScoreMap = new HashMap<>();
        List<ResumeJobAnalysis> analyses = resumeJobAnalysisMapper.selectList(new LambdaQueryWrapper<ResumeJobAnalysis>()
                .select(ResumeJobAnalysis::getJobId, ResumeJobAnalysis::getOverallScore)
                .eq(ResumeJobAnalysis::getUserId, userId)
                .in(ResumeJobAnalysis::getJobId, jobIds)
                .isNotNull(ResumeJobAnalysis::getOverallScore)
                .orderByDesc(ResumeJobAnalysis::getOverallScore));
        for (ResumeJobAnalysis a : analyses) {
            matchScoreMap.putIfAbsent(a.getJobId(), a.getOverallScore() == null ? null : a.getOverallScore().doubleValue());
        }

        // 3. 批量加载模拟面试场次与最高面试得分
        Map<Long, Integer> interviewCountMap = new HashMap<>();
        Map<Long, Double> bestScoreMap = new HashMap<>();
        List<Interview> interviews = interviewMapper.selectList(new LambdaQueryWrapper<Interview>()
                .select(Interview::getId, Interview::getJobId)
                .eq(Interview::getUserId, userId)
                .in(Interview::getJobId, jobIds));
        if (!interviews.isEmpty()) {
            Map<Long, Long> interviewToJobMap = new HashMap<>();
            List<Long> interviewIds = new ArrayList<>();
            for (Interview iv : interviews) {
                if (iv.getJobId() == null) continue;
                interviewCountMap.merge(iv.getJobId(), 1, Integer::sum);
                interviewToJobMap.put(iv.getId(), iv.getJobId());
                interviewIds.add(iv.getId());
            }
            if (!interviewIds.isEmpty()) {
                List<InterviewReport> reports = interviewReportMapper.selectList(new LambdaQueryWrapper<InterviewReport>()
                        .select(InterviewReport::getInterviewId, InterviewReport::getOverallScore)
                        .in(InterviewReport::getInterviewId, interviewIds));
                for (InterviewReport rep : reports) {
                    if (rep.getOverallScore() == null) continue;
                    Long jId = interviewToJobMap.get(rep.getInterviewId());
                    if (jId != null) {
                        bestScoreMap.merge(jId, rep.getOverallScore().doubleValue(), Math::max);
                    }
                }
            }
        }

        // 4. 按阶段分桶
        Map<String, List<JobKanbanCardVO>> columnCards = new HashMap<>();
        for (String st : KANBAN_STAGES) {
            columnCards.put(st, new ArrayList<>());
        }

        for (Job job : allJobs) {
            String stage = job.getApplicationStage();
            if (!KANBAN_STAGES.contains(stage)) {
                stage = "WISHLIST";
            }
            JobKanbanCardVO card = toCardVO(job, resumeNameMap, matchScoreMap, interviewCountMap, bestScoreMap);
            columnCards.get(stage).add(card);
        }

        return KANBAN_STAGES.stream()
                .map(st -> {
                    List<JobKanbanCardVO> list = columnCards.get(st);
                    return new JobKanbanColumnVO(st, STAGE_TITLES.getOrDefault(st, st), list.size(), list);
                })
                .toList();
    }

    @Override
    public JobKanbanStatsVO getKanbanStats() {
        Long userId = SecurityUtils.getUserId();
        List<Job> allJobs = list(new LambdaQueryWrapper<Job>()
                .select(Job::getId, Job::getApplicationStage, Job::getNextActionAt)
                .eq(Job::getUserId, userId));

        JobKanbanStatsVO stats = new JobKanbanStatsVO();
        stats.setTotalTracked((long) allJobs.size());

        long appliedCount = allJobs.stream()
                .filter(j -> !"WISHLIST".equalsIgnoreCase(j.getApplicationStage()))
                .count();
        stats.setAppliedCount(appliedCount);

        long interviewingCount = allJobs.stream()
                .filter(j -> {
                    String st = j.getApplicationStage();
                    return "INTERVIEW_1".equalsIgnoreCase(st) || "INTERVIEW_2".equalsIgnoreCase(st) || "HR".equalsIgnoreCase(st);
                })
                .count();
        stats.setInterviewingCount(interviewingCount);

        long offerCount = allJobs.stream()
                .filter(j -> "OFFER".equalsIgnoreCase(j.getApplicationStage()))
                .count();
        stats.setOfferCount(offerCount);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime in7Days = now.plusDays(7);
        long upcoming = allJobs.stream()
                .filter(j -> j.getNextActionAt() != null && !j.getNextActionAt().isBefore(now) && !j.getNextActionAt().isAfter(in7Days))
                .count();
        stats.setUpcomingScheduleCount(upcoming);

        return stats;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobKanbanCardVO updateStage(Long id, JobStageUpdateDTO dto) {
        Job job = requireOwned(id);
        String stage = dto.getStage().trim().toUpperCase();
        job.setApplicationStage(stage);
        updateById(job);

        auditService.recordSuccess("JOB_STAGE_UPDATE",
                AuditConstants.RESOURCE_JOB, String.valueOf(id),
                Map.of("stage", stage));

        return getCardVO(job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobKanbanCardVO updateCrm(Long id, JobCrmUpdateDTO dto) {
        Job job = requireOwned(id);

        if (StringUtils.hasText(dto.getApplicationStage())) {
            job.setApplicationStage(dto.getApplicationStage().trim().toUpperCase());
        }
        if (dto.getLinkedResumeId() != null) {
            if (StringUtils.hasText(dto.getLinkedResumeId())) {
                try {
                    long rid = Long.parseLong(dto.getLinkedResumeId().trim());
                    Resume resume = resumeMapper.selectById(rid);
                    if (resume == null || !resume.getUserId().equals(SecurityUtils.getUserId())) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "所选简历不存在或不属于当前用户");
                    }
                    job.setLinkedResumeId(rid);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "非法的简历 ID");
                }
            } else {
                job.setLinkedResumeId(null);
            }
        }
        if (dto.getSalaryOffer() != null) {
            job.setSalaryOffer(dto.getSalaryOffer().trim());
        }
        if (dto.getNextActionAt() != null) {
            job.setNextActionAt(dto.getNextActionAt());
        }
        if (dto.getCrmNotes() != null) {
            job.setCrmNotes(dto.getCrmNotes().trim());
        }
        updateById(job);

        auditService.recordSuccess("JOB_CRM_UPDATE",
                AuditConstants.RESOURCE_JOB, String.valueOf(id),
                Map.of("stage", job.getApplicationStage() == null ? "" : job.getApplicationStage()));

        return getCardVO(job);
    }

    private JobKanbanCardVO getCardVO(Job job) {
        Map<Long, String> resumeMap = new HashMap<>();
        if (job.getLinkedResumeId() != null) {
            Resume r = resumeMapper.selectById(job.getLinkedResumeId());
            if (r != null) {
                resumeMap.put(r.getId(), r.getName());
            }
        }

        Map<Long, Double> matchScoreMap = new HashMap<>();
        ResumeJobAnalysis a = resumeJobAnalysisMapper.selectOne(new LambdaQueryWrapper<ResumeJobAnalysis>()
                .select(ResumeJobAnalysis::getOverallScore)
                .eq(ResumeJobAnalysis::getUserId, job.getUserId())
                .eq(ResumeJobAnalysis::getJobId, job.getId())
                .isNotNull(ResumeJobAnalysis::getOverallScore)
                .orderByDesc(ResumeJobAnalysis::getOverallScore)
                .last("LIMIT 1"));
        if (a != null && a.getOverallScore() != null) {
            matchScoreMap.put(job.getId(), a.getOverallScore().doubleValue());
        }

        Map<Long, Integer> countMap = new HashMap<>();
        Map<Long, Double> bestScoreMap = new HashMap<>();
        List<Interview> ivs = interviewMapper.selectList(new LambdaQueryWrapper<Interview>()
                .select(Interview::getId, Interview::getJobId)
                .eq(Interview::getUserId, job.getUserId())
                .eq(Interview::getJobId, job.getId()));
        if (!ivs.isEmpty()) {
            countMap.put(job.getId(), ivs.size());
            List<Long> ivIds = ivs.stream().map(Interview::getId).toList();
            List<InterviewReport> reps = interviewReportMapper.selectList(new LambdaQueryWrapper<InterviewReport>()
                    .select(InterviewReport::getOverallScore)
                    .in(InterviewReport::getInterviewId, ivIds));
            for (InterviewReport rep : reps) {
                if (rep.getOverallScore() != null) {
                    bestScoreMap.merge(job.getId(), rep.getOverallScore().doubleValue(), Math::max);
                }
            }
        }

        return toCardVO(job, resumeMap, matchScoreMap, countMap, bestScoreMap);
    }

    private JobKanbanCardVO toCardVO(Job job,
                                     Map<Long, String> resumeNameMap,
                                     Map<Long, Double> matchScoreMap,
                                     Map<Long, Integer> interviewCountMap,
                                     Map<Long, Double> bestScoreMap) {
        JobKanbanCardVO card = new JobKanbanCardVO();
        card.setId(String.valueOf(job.getId()));
        card.setTitle(job.getTitle());
        card.setCompanyName(job.getCompanyName());
        card.setLocation(job.getLocation());
        card.setExperienceRequired(job.getExperienceRequired());
        card.setEducationRequired(job.getEducationRequired());
        card.setParseStatus(job.getParseStatus());
        String stage = job.getApplicationStage();
        card.setApplicationStage(KANBAN_STAGES.contains(stage) ? stage : "WISHLIST");
        if (job.getLinkedResumeId() != null) {
            card.setLinkedResumeId(String.valueOf(job.getLinkedResumeId()));
            card.setLinkedResumeName(resumeNameMap.get(job.getLinkedResumeId()));
        }
        card.setSalaryOffer(job.getSalaryOffer());
        card.setNextActionAt(job.getNextActionAt());
        card.setCrmNotes(job.getCrmNotes());
        card.setMatchScore(matchScoreMap.get(job.getId()));
        card.setInterviewCount(interviewCountMap.getOrDefault(job.getId(), 0));
        card.setBestInterviewScore(bestScoreMap.get(job.getId()));
        card.setCreatedAt(job.getCreatedAt());
        card.setUpdatedAt(job.getUpdatedAt());
        return card;
    }
}
