package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.client.AiServiceClient.InterviewAnalyzeData;
import com.zest.mindforgeai.client.AiServiceClient.InterviewAnalyzeRequest;
import com.zest.mindforgeai.client.AiServiceClient.InterviewGenerateData;
import com.zest.mindforgeai.client.AiServiceClient.InterviewGenerateRequest;
import com.zest.mindforgeai.client.AiServiceClient.InterviewReportData;
import com.zest.mindforgeai.client.AiServiceClient.InterviewReportRequest;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.InterviewMapper;
import com.zest.mindforgeai.mapper.InterviewQuestionMapper;
import com.zest.mindforgeai.mapper.InterviewReportMapper;
import com.zest.mindforgeai.pojo.dto.InterviewAnswerDTO;
import com.zest.mindforgeai.pojo.dto.InterviewCreateDTO;
import com.zest.mindforgeai.pojo.dto.InterviewPracticeDTO;
import com.zest.mindforgeai.pojo.entity.Interview;
import com.zest.mindforgeai.pojo.entity.InterviewQuestion;
import com.zest.mindforgeai.pojo.entity.InterviewReport;
import com.zest.mindforgeai.pojo.vo.InterviewAnalysisVO;
import com.zest.mindforgeai.pojo.vo.InterviewAnswerResultVO;
import com.zest.mindforgeai.pojo.vo.InterviewDetailVO;
import com.zest.mindforgeai.pojo.vo.InterviewMistakeStatsVO;
import com.zest.mindforgeai.pojo.vo.InterviewMistakeVO;
import com.zest.mindforgeai.pojo.vo.InterviewQuestionVO;
import com.zest.mindforgeai.pojo.vo.InterviewReportVO;
import com.zest.mindforgeai.pojo.vo.InterviewVO;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.InterviewService;
import com.zest.mindforgeai.service.JobService;
import com.zest.mindforgeai.service.KnowledgeBaseService;
import com.zest.mindforgeai.service.RecycleBinService;
import com.zest.mindforgeai.service.ResumeService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 面试服务实现
 *
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Service
public class InterviewServiceImpl extends ServiceImpl<InterviewMapper, Interview>
        implements InterviewService {

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    /* 状态。取值对齐 interview.status 列的注释 */
    private static final String CREATED = "CREATED";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String FINISHED = "FINISHED";

    private static final Set<String> INTERVIEW_TYPES = Set.of("TECHNICAL", "BEHAVIORAL", "MIXED");
    private static final Set<String> QUESTION_TYPES = Set.of("TECHNICAL", "BEHAVIORAL", "SITUATIONAL");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Set<String> STATUSES = Set.of(CREATED, IN_PROGRESS, FINISHED);

    /** 错题本掌握分数线：低于 75 分视为待攻克薄弱项，>= 75 分视为已掌握 */
    private static final BigDecimal MASTERY_THRESHOLD = BigDecimal.valueOf(75.0);

    private static final int DEFAULT_QUESTION_COUNT = 5;
    /** 上限对齐 Python 侧 InterviewGenerateRequest.questionCount 的 le=20 */
    private static final int MAX_QUESTION_COUNT = 20;

    /** 与 interview.title varchar(255) 对齐 */
    private static final int MAX_TITLE_LEN = 255;

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。 */
    private static final Map<String, SFunction<Interview, ?>> SORTABLE = Map.of(
            "title", Interview::getTitle,
            "status", Interview::getStatus,
            "createdAt", Interview::getCreatedAt,
            "finishedAt", Interview::getFinishedAt);

    /** 进程内防并发重入集合，防止同一场面试并发触发 generate / finish 导致多次调用 LLM 和覆盖题目 */
    private final Set<Long> inFlightIds = ConcurrentHashMap.newKeySet();

    private final InterviewQuestionMapper questionMapper;
    private final InterviewReportMapper reportMapper;
    private final AiServiceClient aiServiceClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ResumeService resumeService;
    private final JobService jobService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final TransactionTemplate transactionTemplate;
    private final RecycleBinService recycleBinService;

    public InterviewServiceImpl(InterviewQuestionMapper questionMapper,
                                InterviewReportMapper reportMapper,
                                AiServiceClient aiServiceClient,
                                AuditService auditService,
                                ObjectMapper objectMapper,
                                ResumeService resumeService,
                                JobService jobService,
                                KnowledgeBaseService knowledgeBaseService,
                                TransactionTemplate transactionTemplate,
                                RecycleBinService recycleBinService) {
        this.questionMapper = questionMapper;
        this.reportMapper = reportMapper;
        this.aiServiceClient = aiServiceClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.transactionTemplate = transactionTemplate;
        this.recycleBinService = recycleBinService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InterviewVO create(InterviewCreateDTO dto) {
        String interviewType = enumOrDefault(dto.getInterviewType(), INTERVIEW_TYPES, "TECHNICAL");
        String difficulty = enumOrDefault(dto.getDifficulty(), DIFFICULTIES, "MEDIUM");
        int planned = clampQuestionCount(dto.getQuestionCount());

        // 关联的简历 / 岗位【在这里】就校验归属，不留到出题时 ——
        // 失败要早：让用户在点「创建」时就知道选错了，而不是等了几十秒出题才发现。
        Long resumeId = null;
        String resumeName = null;
        if (StringUtils.hasText(dto.getResumeId())) {
            ResumeDetailVO resume = resumeService.getDetail(parseId(dto.getResumeId(), "简历 id"));
            resumeId = parseId(resume.getId(), "简历 id");
            resumeName = resume.getName();
        }
        Long jobId = null;
        String jobTitle = null;
        if (StringUtils.hasText(dto.getJobId())) {
            JobDetailVO job = jobService.getDetail(parseId(dto.getJobId(), "职位 id"));
            jobId = parseId(job.getId(), "职位 id");
            jobTitle = job.getTitle();
        }

        String title = StringUtils.hasText(dto.getTitle())
                ? dto.getTitle().trim()
                : defaultTitle(jobTitle, resumeName);
        if (title.length() > MAX_TITLE_LEN) {
            title = title.substring(0, MAX_TITLE_LEN);
        }

        Interview interview = new Interview()
                .setUserId(SecurityUtils.getUserId())
                .setResumeId(resumeId)
                .setJobId(jobId)
                .setTitle(title)
                .setInterviewType(interviewType)
                .setDifficulty(difficulty)
                .setStatus(CREATED)
                // 先记「打算出几道」，出题成功后按实际题数覆盖（正常情况下相同）。
                // 所以这个字段的语义始终是「这场面试有几道题」，不是「出题了没」——
                // 后者要看题目表里有没有行。
                .setQuestionCount(planned);
        save(interview);

        log.info("创建面试 interviewId={} type={} 题数={} 关联简历={} 关联岗位={}",
                interview.getId(), interviewType, planned, resumeId, jobId);

        auditService.recordSuccess(AuditConstants.ACTION_INTERVIEW_CREATE,
                AuditConstants.RESOURCE_INTERVIEW, String.valueOf(interview.getId()),
                Map.of("interviewType", interviewType,
                       "difficulty", difficulty,
                       "questionCount", planned));

        return toVO(interview, 0, 0, null);
    }

    @Override
    public PageResultVO<InterviewVO> pageInterviews(long page, long pageSize,
                                                    String keyword, String status,
                                                    String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        String safeKeyword = SortSupport.escapeLike(keyword);
        LambdaQueryWrapper<Interview> wrapper = new LambdaQueryWrapper<Interview>()
                .eq(Interview::getUserId, SecurityUtils.getUserId())
                .like(StringUtils.hasText(safeKeyword), Interview::getTitle, safeKeyword)
                .eq(StringUtils.hasText(status), Interview::getStatus, normalizeStatus(status));

        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, Interview::getId, false);

        Page<Interview> result = page(new Page<>(safePage, safeSize), wrapper);

        // 一次查出这一页所有面试的题目（只取三个轻量列），在内存里算进度。
        // 比每行一次 count/avg 查询好得多，也不用为此写自定义 SQL。
        Map<Long, Progress> progress = loadProgress(
                result.getRecords().stream().map(Interview::getId).toList());

        List<InterviewVO> records = result.getRecords().stream()
                .map(i -> toVO(i, progress))
                .toList();

        return PageResultVO.of(result, records);
    }

    @Override
    public InterviewDetailVO getDetail(Long id) {
        Interview interview = requireOwned(id);
        return toDetailVO(interview, listQuestions(id));
    }

    @Override
    public InterviewDetailVO generate(Long id) {
        Interview interview = requireOwned(id);
        if (!inFlightIds.add(id)) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "正在生成题目中，请勿重复提交");
        }
        try {
            if (FINISHED.equals(interview.getStatus())) {
                throw new BizException(ErrorCode.STATUS_CONFLICT, "面试已结束，不能再重新出题");
            }
            if (IN_PROGRESS.equals(interview.getStatus())) {
                // 已经答了一半再换一套题，用户答过的就全对不上了
                throw new BizException(ErrorCode.STATUS_CONFLICT, "面试已开始作答，不能再重新出题");
            }

            String resumeText = null;
            if (interview.getResumeId() != null) {
                try {
                    ResumeDetailVO resume = resumeService.getDetail(interview.getResumeId());
                    resumeText = resume.getRawContent();
                    // 简历没解析就没内容可问 —— 让用户先去解析，
                    // 而不是拿一份空简历让模型瞎出题（出来的题会完全跑偏）。
                    if (!StringUtils.hasText(resumeText)) {
                        throw new BizException(ErrorCode.PARAM_INVALID,
                                "关联的简历还没有解析过，请先解析再出题");
                    }
                } catch (BizException e) {
                    if (e.getCode() == ErrorCode.RESUME_NOT_FOUND.getCode()) {
                        log.warn("面试 {} 关联的简历 {} 已删除，软回退为按岗位/通用题库出题",
                                id, interview.getResumeId());
                        resumeText = null;
                    } else {
                        throw e;
                    }
                }
            }

            String jobText = null;
            String jobTitle = null;
            if (interview.getJobId() != null) {
                try {
                    JobDetailVO job = jobService.getDetail(interview.getJobId());
                    jobText = job.getRawContent();
                    jobTitle = job.getTitle();
                    // JD 没解析只是少一点针对性，不拦 —— 出题主要靠简历。
                } catch (BizException e) {
                    if (e.getCode() == ErrorCode.JOB_NOT_FOUND.getCode()) {
                        log.warn("面试 {} 关联的职位 {} 已删除，软回退为不针对具体岗位出题",
                                id, interview.getJobId());
                    } else {
                        throw e;
                    }
                }
            }

            if (!StringUtils.hasText(resumeText) && !StringUtils.hasText(jobText)) {
                jobText = StringUtils.hasText(interview.getTitle())
                        ? interview.getTitle()
                        : "通用技术岗位";
            }

            String systemKbIds = knowledgeBaseService.resolveDualTrackKbIds(null);
            InterviewGenerateData data = aiServiceClient.generateQuestions(new InterviewGenerateRequest(
                    String.valueOf(interview.getUserId()),
                    String.valueOf(id),
                    interview.getInterviewType(),
                    interview.getDifficulty(),
                    clampQuestionCount(interview.getQuestionCount()),
                    resumeText,
                    jobText,
                    systemKbIds));

            List<InterviewGenerateData.Question> questions =
                    data.questions() == null ? List.of() : data.questions();

            // Python 侧正常不会返回空（它自己会兜底），真返回了说明模型抽风。
            // 不报错的话会留下一个「0 道题但状态正常」的面试 —— 用户点开始就卡在那里，
            // 而且看不出是出题失败了。
            if (questions.isEmpty()) {
                throw new BizException(ErrorCode.AI_SERVICE_ERROR, "AI 没有生成任何题目，请重试");
            }

            int saved = persistQuestions(id, questions);

            log.info("面试出题 interviewId={} 实际 {} 道（计划 {} 道）岗位={}",
                    id, saved, interview.getQuestionCount(), jobTitle);

            auditService.recordSuccess(AuditConstants.ACTION_INTERVIEW_GENERATE,
                    AuditConstants.RESOURCE_INTERVIEW, String.valueOf(id),
                    Map.of("questionCount", saved));

            return getDetail(id);
        } finally {
            inFlightIds.remove(id);
        }
    }

    @Override
    public InterviewDetailVO start(Long id) {
        Interview interview = requireOwned(id);

        if (FINISHED.equals(interview.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "面试已结束");
        }

        // 「有没有出题」看题目表，不看 interview.question_count ——
        // 后者是「打算出几道」，创建时就有值了，拿它判断会把「还没出题」当成「已出题」。
        if (countQuestions(id) == 0) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "这场面试还没有题目，请先出题");
        }

        if (CREATED.equals(interview.getStatus())) {
            // 条件更新（带 status = CREATED）：并发重复点「开始」时只有一个能改成功，
            // 另一个的 updated 影响行数是 0，直接跳过即可 —— 不需要报错，
            // 因为「开始」这个动作本身是幂等的。
            update(new LambdaUpdateWrapper<Interview>()
                    .eq(Interview::getId, id)
                    .eq(Interview::getStatus, CREATED)
                    .set(Interview::getStatus, IN_PROGRESS)
                    .set(Interview::getStartedAt, LocalDateTime.now())
                    .set(Interview::getUpdatedAt, LocalDateTime.now()));

            log.info("开始面试 interviewId={} 题数={}", id, interview.getQuestionCount());
        }

        return getDetail(id);
    }

    @Override
    public InterviewAnswerResultVO answer(Long id, InterviewAnswerDTO dto) {
        Interview interview = requireOwned(id);

        if (FINISHED.equals(interview.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "面试已结束，不能再作答");
        }
        if (CREATED.equals(interview.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "面试还没有开始，请先开始面试");
        }

        InterviewQuestion question = questionMapper.selectById(
                parseId(dto.getQuestionId(), "题目 id"));
        // 不存在、或者存在但不属于这场面试，都按「题目不存在」报 ——
        // 同 requireOwned 的理由：不泄漏「这个 id 存在」这个事实。
        if (question == null || !question.getInterviewId().equals(id)) {
            throw new BizException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
        }

        // 空回答是允许的（跳过一题），给空串而不是 null ——
        // Python 侧对 None 和 "" 的处理是一样的，但传空串语义更明确。
        String answer = dto.getAnswer() == null ? "" : dto.getAnswer();

        InterviewAnalyzeData data = aiServiceClient.analyzeAnswer(new InterviewAnalyzeRequest(
                String.valueOf(interview.getUserId()),
                String.valueOf(id),
                question.getQuestion(),
                answer,
                parseStringList(question.getExpectedPoints()),
                interview.getInterviewType()));

        saveAnswer(question.getId(), answer, data);

        List<InterviewQuestion> all = listQuestions(id);
        InterviewQuestion next = all.stream()
                .filter(q -> q.getAnsweredAt() == null)
                .findFirst()            // listQuestions 已按题号升序，所以拿到的是题号最小的未答题
                .orElse(null);
        int answered = (int) all.stream().filter(q -> q.getAnsweredAt() != null).count();

        log.info("面试作答 interviewId={} 题号={} 得分={} 进度={}/{}",
                id, question.getQuestionNo(), data.score(), answered, all.size());

        auditService.recordSuccess(AuditConstants.ACTION_INTERVIEW_ANSWER,
                AuditConstants.RESOURCE_INTERVIEW, String.valueOf(id),
                Map.of("questionNo", question.getQuestionNo(),
                       "score", data.score() == null ? 0 : data.score(),
                       "answerLength", answer.length()));

        InterviewAnswerResultVO vo = new InterviewAnswerResultVO();
        vo.setQuestionId(String.valueOf(question.getId()));
        vo.setQuestionNo(question.getQuestionNo());
        vo.setScore(data.score());
        vo.setAnalysis(toAnalysisVO(data));
        vo.setNextQuestion(next == null ? null : toNextQuestionVO(next));
        vo.setAnsweredCount(answered);
        vo.setTotalCount(all.size());
        vo.setAllAnswered(next == null);
        return vo;
    }

    @Override
    public InterviewReportVO finish(Long id) {
        Interview interview = requireOwned(id);
        if (!inFlightIds.add(id)) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "正在生成面试报告中，请勿重复提交");
        }
        try {
            List<InterviewQuestion> questions = listQuestions(id);
            if (questions.isEmpty()) {
                throw new BizException(ErrorCode.STATUS_CONFLICT, "这场面试还没有题目");
            }
            // 一道都没答就没有素材可复盘。让模型凭空写一份「报告」，
            // 写出来的只能是套话，而用户会以为那是针对自己的评价 —— 比没有报告更糟。
            if (questions.stream().noneMatch(q -> q.getAnsweredAt() != null)) {
                throw new BizException(ErrorCode.STATUS_CONFLICT, "还没有作答任何题目，无法生成报告");
            }

            // ★ 未作答的题也带上（answer / score 为 null）——
            //   报告里要能说出「有 2 题没答」，只给答过的题模型是看不到这个事实的。
            List<InterviewReportRequest.ReportItem> items = questions.stream()
                    .map(q -> new InterviewReportRequest.ReportItem(
                            q.getQuestionNo(),
                            q.getQuestion(),
                            q.getUserAnswer(),
                            q.getScore() == null ? null : q.getScore().doubleValue(),
                            parseJsonNode(q.getAiAnalysis())))
                    .toList();

            String jobTitle = null;
            if (interview.getJobId() != null) {
                try {
                    jobTitle = jobService.getDetail(interview.getJobId()).getTitle();
                } catch (BizException e) {
                    log.warn("面试 {} 结束时关联职位 {} 已不可用（{}），软回退按通用岗位生成报告",
                            id, interview.getJobId(), e.getMessage());
                }
            }

            InterviewReportData data = aiServiceClient.buildReport(new InterviewReportRequest(
                    String.valueOf(interview.getUserId()),
                    String.valueOf(id),
                    interview.getInterviewType(),
                    jobTitle,
                    items));

            persistReport(id, data);

            log.info("面试报告 interviewId={} 综合={} 技术={} 沟通={} 问题解决={}",
                    id, data.overallScore(), data.technicalScore(),
                    data.communicationScore(), data.problemSolvingScore());

            auditService.recordSuccess(AuditConstants.ACTION_INTERVIEW_FINISH,
                    AuditConstants.RESOURCE_INTERVIEW, String.valueOf(id),
                    Map.of("questionCount", questions.size(),
                           "overallScore", data.overallScore() == null ? 0 : data.overallScore()));

            return getReport(id);
        } finally {
            inFlightIds.remove(id);
        }
    }

    @Override
    public InterviewReportVO getReport(Long id) {
        requireOwned(id);

        InterviewReport report = reportMapper.selectOne(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getInterviewId, id));
        // 还没结束（或结束但生成失败）时是「不存在」，不是「空报告」——
        // 返回一个字段全 null 的对象会让前端以为是「报告生成出来了但是空的」。
        if (report == null) {
            throw new BizException(ErrorCode.INTERVIEW_REPORT_NOT_FOUND);
        }
        return toReportVO(report);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Interview interview = requireOwned(id);

        // ★ 必须在物理删之前登记回收站：put 会让 InterviewRecycleHandler 采集
        //   interview_question / interview_report 的快照。这两张表没有 deleted 列，
        //   删完再调就只能采到空集合 —— 而题目和报告是花 AI token 生成的。
        recycleBinService.put(RecycleResourceType.INTERVIEW, id,
                SecurityUtils.getUserId(), interview.getTitle());

        // ① 逻辑删面试（MP 自动设 deleted = 1）
        removeById(id);

        // ② 题目和报告【物理删】—— 这两张表没有 deleted 列。
        //    本项目所有表都没有外键，所以必须手动清，而且要在同一个事务里
        //    （清一半会留下孤儿数据）。
        int questions = questionMapper.delete(new LambdaQueryWrapper<InterviewQuestion>()
                .eq(InterviewQuestion::getInterviewId, id));
        int reports = reportMapper.delete(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getInterviewId, id));

        log.info("删除面试 interviewId={} title={}（连带清掉 {} 道题、{} 份报告）",
                id, interview.getTitle(), questions, reports);

        auditService.recordSuccess(AuditConstants.ACTION_INTERVIEW_DELETE,
                AuditConstants.RESOURCE_INTERVIEW, String.valueOf(id),
                Map.of("title", interview.getTitle(),
                       "questionCount", questions));
    }

    @Override
    public InterviewMistakeStatsVO getMistakeStats() {
        List<Interview> finished = list(new LambdaQueryWrapper<Interview>()
                .select(Interview::getId)
                .eq(Interview::getUserId, SecurityUtils.getUserId())
                .eq(Interview::getStatus, FINISHED));

        InterviewMistakeStatsVO vo = new InterviewMistakeStatsVO();
        if (finished.isEmpty()) {
            vo.setTotalAnswered(0L);
            vo.setWeakCount(0L);
            vo.setMasteredCount(0L);
            vo.setAverageScore(null);
            return vo;
        }

        List<Long> interviewIds = finished.stream().map(Interview::getId).toList();
        List<InterviewQuestion> answered = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .select(InterviewQuestion::getScore)
                        .in(InterviewQuestion::getInterviewId, interviewIds)
                        .isNotNull(InterviewQuestion::getAnsweredAt));

        long total = answered.size();
        long weak = 0L;
        long mastered = 0L;
        BigDecimal sum = BigDecimal.ZERO;

        for (InterviewQuestion q : answered) {
            BigDecimal s = q.getScore() == null ? BigDecimal.ZERO : q.getScore();
            sum = sum.add(s);
            if (s.compareTo(MASTERY_THRESHOLD) < 0) {
                weak++;
            } else {
                mastered++;
            }
        }

        vo.setTotalAnswered(total);
        vo.setWeakCount(weak);
        vo.setMasteredCount(mastered);
        vo.setAverageScore(total == 0 ? null
                : sum.divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP).doubleValue());
        return vo;
    }

    @Override
    public PageResultVO<InterviewMistakeVO> pageMistakes(long page, long pageSize,
                                                         String masteryStatus,
                                                         String questionType,
                                                         String difficulty,
                                                         String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        List<Interview> finished = list(new LambdaQueryWrapper<Interview>()
                .select(Interview::getId, Interview::getTitle, Interview::getInterviewType)
                .eq(Interview::getUserId, SecurityUtils.getUserId())
                .eq(Interview::getStatus, FINISHED));

        if (finished.isEmpty()) {
            return PageResultVO.of(new Page<>(safePage, safeSize, 0), List.of());
        }

        Map<Long, Interview> interviewMap = finished.stream()
                .collect(Collectors.toMap(Interview::getId, Function.identity(), (a, b) -> a));

        String mode = StringUtils.hasText(masteryStatus) ? masteryStatus.trim().toUpperCase() : "WEAK";
        String safeKeyword = SortSupport.escapeLike(keyword);
        String qType = StringUtils.hasText(questionType) ? questionType.trim().toUpperCase() : null;
        if (qType != null && !QUESTION_TYPES.contains(qType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的题型：" + questionType);
        }
        String diff = StringUtils.hasText(difficulty) ? difficulty.trim().toUpperCase() : null;
        if (diff != null && !DIFFICULTIES.contains(diff)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的难度：" + difficulty);
        }

        LambdaQueryWrapper<InterviewQuestion> wrapper = new LambdaQueryWrapper<InterviewQuestion>()
                .in(InterviewQuestion::getInterviewId, interviewMap.keySet())
                .isNotNull(InterviewQuestion::getAnsweredAt)
                .lt("WEAK".equals(mode), InterviewQuestion::getScore, MASTERY_THRESHOLD)
                .ge("MASTERED".equals(mode), InterviewQuestion::getScore, MASTERY_THRESHOLD)
                .eq(qType != null, InterviewQuestion::getQuestionType, qType)
                .eq(diff != null, InterviewQuestion::getDifficulty, diff)
                .like(StringUtils.hasText(safeKeyword), InterviewQuestion::getQuestion, safeKeyword)
                .orderByAsc(InterviewQuestion::getScore)
                .orderByDesc(InterviewQuestion::getAnsweredAt)
                .orderByDesc(InterviewQuestion::getId);

        Page<InterviewQuestion> result = questionMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<InterviewMistakeVO> records = result.getRecords().stream()
                .map(q -> toMistakeVO(q, interviewMap.get(q.getInterviewId())))
                .toList();

        return PageResultVO.of(result, records);
    }

    @Override
    public InterviewMistakeVO practiceQuestion(Long questionId, InterviewPracticeDTO dto) {
        InterviewQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BizException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
        }

        Interview interview = requireOwned(question.getInterviewId());
        if (!FINISHED.equals(interview.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "该场面试尚未结束，请先在面试流程中完成作答");
        }

        String answer = dto == null || dto.getAnswer() == null ? "" : dto.getAnswer().trim();
        if (!StringUtils.hasText(answer)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "重练回答内容不能为空");
        }

        InterviewAnalyzeData data = aiServiceClient.analyzeAnswer(new InterviewAnalyzeRequest(
                String.valueOf(interview.getUserId()),
                String.valueOf(interview.getId()),
                question.getQuestion(),
                answer,
                parseStringList(question.getExpectedPoints()),
                interview.getInterviewType()));

        saveAnswer(question.getId(), answer, data);

        log.info("错题本重练 questionId={} interviewId={} 原得分={} 新得分={}",
                questionId, interview.getId(), question.getScore(), data.score());

        auditService.recordSuccess(AuditConstants.ACTION_INTERVIEW_ANSWER,
                AuditConstants.RESOURCE_INTERVIEW, String.valueOf(interview.getId()),
                Map.of("questionId", String.valueOf(questionId),
                       "questionNo", question.getQuestionNo(),
                       "practice", true,
                       "score", data.score() == null ? 0 : data.score()));

        InterviewQuestion updated = questionMapper.selectById(questionId);
        return toMistakeVO(updated, interview);
    }

    private InterviewMistakeVO toMistakeVO(InterviewQuestion question, Interview interview) {
        InterviewMistakeVO vo = new InterviewMistakeVO();
        vo.setQuestionId(String.valueOf(question.getId()));
        vo.setInterviewId(String.valueOf(question.getInterviewId()));
        vo.setInterviewTitle(interview == null ? "历史模拟面试" : interview.getTitle());
        vo.setInterviewType(interview == null ? "TECHNICAL" : interview.getInterviewType());
        vo.setQuestionNo(question.getQuestionNo());
        vo.setQuestionType(question.getQuestionType());
        vo.setDifficulty(question.getDifficulty());
        vo.setQuestion(question.getQuestion());
        vo.setUserAnswer(question.getUserAnswer());
        Double score = question.getScore() == null ? null : question.getScore().doubleValue();
        vo.setScore(score);
        vo.setMastered(question.getScore() != null && question.getScore().compareTo(MASTERY_THRESHOLD) >= 0);
        vo.setAiAnalysis(toAnalysisVO(question.getAiAnalysis()));
        vo.setExpectedPoints(parseStringList(question.getExpectedPoints()));
        vo.setAnsweredAt(question.getAnsweredAt());
        return vo;
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 取面试并校验归属。
     * <p>
     * 不存在、或者存在但不属于当前用户，都抛 {@code INTERVIEW_NOT_FOUND} ——
     * <b>刻意不区分</b>：抛 403 等于告诉调用方「这个 id 存在，只是不归你」，
     * 相当于帮他确认了别人的数据范围。抛 404 什么都不泄漏。
     */
    private Interview requireOwned(Long id) {
        Interview interview = getById(id);
        if (interview == null || !interview.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.INTERVIEW_NOT_FOUND);
        }
        return interview;
    }

    /** 按题号升序取全部题目 —— 顺序很重要，「下一题」就是靠它取第一个未作答的 */
    private List<InterviewQuestion> listQuestions(Long interviewId) {
        return questionMapper.selectList(new LambdaQueryWrapper<InterviewQuestion>()
                .eq(InterviewQuestion::getInterviewId, interviewId)
                .orderByAsc(InterviewQuestion::getQuestionNo));
    }

    private long countQuestions(Long interviewId) {
        return questionMapper.selectCount(new LambdaQueryWrapper<InterviewQuestion>()
                .eq(InterviewQuestion::getInterviewId, interviewId));
    }

    /**
     * 落题（一个事务）。
     * <p>
     * ★ <b>先清后出</b>：让「重试出题」和「换一套题」都走同一条路，
     * 不用判断「是不是第一次出」。同时也避免了并发重复出题撞
     * {@code uk_interview_question_no} 唯一键。
     * <p>
     * ⚠️ {@code question_no} 这里<b>自己重排</b>成 1..N，不信模型给的编号 ——
     * 模型经常从 0 开始、跳号，或者几道题共用一个号，
     * 而唯一键和「下一题」的逻辑都依赖它连续。
     *
     * @return 实际落库的题数（题干为空的条目会被丢掉）
     */
    private int persistQuestions(Long interviewId, List<InterviewGenerateData.Question> questions) {
        int[] saved = new int[1];

        transactionTemplate.executeWithoutResult(tx -> {
            questionMapper.delete(new LambdaQueryWrapper<InterviewQuestion>()
                    .eq(InterviewQuestion::getInterviewId, interviewId));

            for (InterviewGenerateData.Question src : questions) {
                if (!StringUtils.hasText(src.question())) {
                    continue;       // 没有题干的条目直接丢
                }
                saved[0]++;
                questionMapper.insert(new InterviewQuestion()
                        .setInterviewId(interviewId)
                        .setQuestionNo(saved[0])
                        .setQuestionType(src.questionType())
                        .setQuestion(src.question())
                        .setExpectedPoints(toJson(src.expectedPoints()))
                        .setDifficulty(src.difficulty()));
            }

            // 按实际题数回写：用户选的 5 道，模型可能只出了 4 道，
            // 这时候 question_count 必须是 4，否则前端按 5 道去算进度会永远差一题。
            update(new LambdaUpdateWrapper<Interview>()
                    .eq(Interview::getId, interviewId)
                    .set(Interview::getQuestionCount, saved[0])
                    .set(Interview::getUpdatedAt, LocalDateTime.now()));
        });

        return saved[0];
    }

    /** 落单题的回答 + 分析 + 分数（一个事务，虽然只有一条 UPDATE） */
    private void saveAnswer(Long questionId, String answer, InterviewAnalyzeData data) {
        transactionTemplate.executeWithoutResult(tx ->
                questionMapper.update(null, new LambdaUpdateWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getId, questionId)
                        .set(InterviewQuestion::getUserAnswer, answer)
                        // 存整段分析 JSON：报告时要原样取出来喂给模型
                        .set(InterviewQuestion::getAiAnalysis, toJson(data))
                        .set(InterviewQuestion::getScore, toDecimal(data.score()))
                        .set(InterviewQuestion::getAnsweredAt, LocalDateTime.now())));
    }

    /**
     * 落报告（一个事务）。
     * <p>
     * ⚠️ 先删后插：一场面试只有一份报告（表上有 {@code uk_report_interview}），
     * 重复调 finish 时是「重新生成并覆盖」，直接 insert 会撞唯一键。
     */
    private void persistReport(Long interviewId, InterviewReportData data) {
        transactionTemplate.executeWithoutResult(tx -> {
            reportMapper.delete(new LambdaQueryWrapper<InterviewReport>()
                    .eq(InterviewReport::getInterviewId, interviewId));

            reportMapper.insert(new InterviewReport()
                    .setInterviewId(interviewId)
                    .setOverallScore(toDecimal(data.overallScore()))
                    .setTechnicalScore(toDecimal(data.technicalScore()))
                    .setCommunicationScore(toDecimal(data.communicationScore()))
                    .setProblemSolvingScore(toDecimal(data.problemSolvingScore()))
                    .setStrengthsJson(toJson(data.strengths()))
                    .setWeaknessesJson(toJson(data.weaknesses()))
                    .setSuggestionsJson(toJson(data.suggestions()))
                    .setReportText(data.reportText())
                    // 用的哪个模型由 Python 侧的配置决定，Java 这边看不到 ——
                    // 留 null 比瞎填一个强（填错了会让人以为报告是别的模型生成的）。
                    .setModelName(null));

            update(new LambdaUpdateWrapper<Interview>()
                    .eq(Interview::getId, interviewId)
                    .set(Interview::getStatus, FINISHED)
                    .set(Interview::getFinishedAt, LocalDateTime.now())
                    .set(Interview::getUpdatedAt, LocalDateTime.now()));
        });
    }

    /**
     * 查这一页面试的作答进度。
     * <p>
     * 只 select 三个轻量列（不取题干），一次查完在内存里聚合 ——
     * 比每行一次 count/avg 好得多，也不用为此写自定义 SQL。
     */
    private Map<Long, Progress> loadProgress(List<Long> interviewIds) {
        List<Long> ids = interviewIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<InterviewQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .select(InterviewQuestion::getInterviewId,
                                InterviewQuestion::getAnsweredAt,
                                InterviewQuestion::getScore)
                        .in(InterviewQuestion::getInterviewId, ids));

        Map<Long, Progress> progress = new HashMap<>();
        for (InterviewQuestion q : questions) {
            Progress before = progress.get(q.getInterviewId());
            int total = (before == null ? 0 : before.total()) + 1;

            // 没作答的只计入总数，不进分数累加
            if (q.getAnsweredAt() == null) {
                progress.put(q.getInterviewId(), new Progress(total,
                        before == null ? 0 : before.answered(),
                        before == null ? BigDecimal.ZERO : before.scoreSum()));
                continue;
            }

            progress.put(q.getInterviewId(), new Progress(total,
                    (before == null ? 0 : before.answered()) + 1,
                    (before == null ? BigDecimal.ZERO : before.scoreSum())
                            .add(q.getScore() == null ? BigDecimal.ZERO : q.getScore())));
        }
        return progress;
    }

    private InterviewVO toVO(Interview interview, Map<Long, Progress> progress) {
        Progress p = progress.get(interview.getId());
        return toVO(interview,
                p == null ? 0 : p.total(),
                p == null ? 0 : p.answered(),
                p == null ? null : p.average());
    }

    private InterviewVO toVO(Interview interview, int questionCount, int answered, Double averageScore) {
        InterviewVO vo = new InterviewVO();
        vo.setId(String.valueOf(interview.getId()));
        vo.setTitle(interview.getTitle());
        vo.setInterviewType(interview.getInterviewType());
        vo.setDifficulty(interview.getDifficulty());
        vo.setStatus(interview.getStatus());
        // ⚠️ 填实际题数（题目行数），不是 interview.question_count ——
        //    后者在「创建了但还没出题」时是个计划值，拿它当「有几道题」会骗到前端。
        vo.setQuestionCount(questionCount);
        vo.setAnsweredCount(answered);
        vo.setAverageScore(averageScore);
        vo.setResumeId(interview.getResumeId() == null ? null : String.valueOf(interview.getResumeId()));
        vo.setJobId(interview.getJobId() == null ? null : String.valueOf(interview.getJobId()));
        vo.setStartedAt(interview.getStartedAt());
        vo.setFinishedAt(interview.getFinishedAt());
        vo.setCreatedAt(interview.getCreatedAt());
        return vo;
    }

    /**
     * 详情 VO。
     * <p>
     * 题目数为 0 时也要把 {@code questionCount} 显示成 0（而不是创建时填的计划值）——
     * 前端的判断是「questionCount &gt; 0 才能开始作答」，给计划值会让按钮亮着但点了报错。
     */
    private InterviewDetailVO toDetailVO(Interview interview, List<InterviewQuestion> questions) {
        // ★ 参考答案要点只在面试【结束后】给出去 —— 进行中给了等于把答案提前泄露，
        //   那这场面试就没有意义了。结束后再给是对的：复盘时能看到标准答案长什么样。
        boolean revealExpectedPoints = FINISHED.equals(interview.getStatus());

        List<InterviewQuestionVO> items = questions.stream()
                .map(q -> toQuestionVO(q, revealExpectedPoints))
                .toList();

        InterviewDetailVO vo = new InterviewDetailVO();
        vo.setId(String.valueOf(interview.getId()));
        vo.setTitle(interview.getTitle());
        vo.setInterviewType(interview.getInterviewType());
        vo.setDifficulty(interview.getDifficulty());
        vo.setStatus(interview.getStatus());
        vo.setQuestionCount(items.size());
        vo.setAnsweredCount((int) questions.stream()
                .filter(q -> q.getAnsweredAt() != null).count());
        vo.setResumeId(interview.getResumeId() == null ? null : String.valueOf(interview.getResumeId()));
        vo.setJobId(interview.getJobId() == null ? null : String.valueOf(interview.getJobId()));
        vo.setStartedAt(interview.getStartedAt());
        vo.setFinishedAt(interview.getFinishedAt());
        vo.setCreatedAt(interview.getCreatedAt());
        vo.setQuestions(items);
        return vo;
    }

    private InterviewQuestionVO toQuestionVO(InterviewQuestion question, boolean revealExpectedPoints) {
        InterviewQuestionVO vo = new InterviewQuestionVO();
        vo.setId(String.valueOf(question.getId()));
        vo.setQuestionNo(question.getQuestionNo());
        vo.setQuestionType(question.getQuestionType());
        vo.setQuestion(question.getQuestion());
        vo.setDifficulty(question.getDifficulty());
        vo.setAnswered(question.getAnsweredAt() != null);
        vo.setUserAnswer(question.getUserAnswer());
        vo.setScore(question.getScore() == null ? null : question.getScore().doubleValue());
        vo.setAiAnalysis(toAnalysisVO(question.getAiAnalysis()));
        vo.setAnsweredAt(question.getAnsweredAt());
        vo.setExpectedPoints(revealExpectedPoints
                ? parseStringList(question.getExpectedPoints())
                : null);
        return vo;
    }

    private InterviewAnswerResultVO.NextQuestionVO toNextQuestionVO(InterviewQuestion question) {
        InterviewAnswerResultVO.NextQuestionVO vo = new InterviewAnswerResultVO.NextQuestionVO();
        vo.setId(String.valueOf(question.getId()));
        vo.setQuestionNo(question.getQuestionNo());
        vo.setQuestion(question.getQuestion());
        vo.setQuestionType(question.getQuestionType());
        vo.setDifficulty(question.getDifficulty());
        return vo;
    }

    /** AI 返回的分析 → VO */
    private InterviewAnalysisVO toAnalysisVO(InterviewAnalyzeData data) {
        InterviewAnalysisVO vo = new InterviewAnalysisVO();
        vo.setScore(data.score());

        if (data.technical() != null) {
            InterviewAnalysisVO.Technical t = new InterviewAnalysisVO.Technical();
            t.setScore(data.technical().score());
            t.setStrengths(nullToEmpty(data.technical().strengths()));
            t.setWeaknesses(nullToEmpty(data.technical().weaknesses()));
            vo.setTechnical(t);
        }
        if (data.communication() != null) {
            InterviewAnalysisVO.Communication c = new InterviewAnalysisVO.Communication();
            c.setClarity(data.communication().clarity());
            c.setIssues(nullToEmpty(data.communication().issues()));
            vo.setCommunication(c);
        }

        vo.setMissingPoints(nullToEmpty(data.missingPoints()));
        vo.setComment(data.comment());
        vo.setFollowUp(data.followUp());
        return vo;
    }

    /** 库里存的分析 JSON → VO。内容损坏时返回 null，不让整个详情 500 */
    private InterviewAnalysisVO toAnalysisVO(String analysisJson) {
        if (!StringUtils.hasText(analysisJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(analysisJson, InterviewAnalysisVO.class);
        } catch (Exception e) {
            log.warn("面试题的分析数据不是合法 JSON，按无分析处理：{}", e.getMessage());
            return null;
        }
    }

    private InterviewReportVO toReportVO(InterviewReport report) {
        InterviewReportVO vo = new InterviewReportVO();
        vo.setId(String.valueOf(report.getId()));
        vo.setInterviewId(String.valueOf(report.getInterviewId()));
        vo.setOverallScore(toDouble(report.getOverallScore()));
        vo.setTechnicalScore(toDouble(report.getTechnicalScore()));
        vo.setCommunicationScore(toDouble(report.getCommunicationScore()));
        vo.setProblemSolvingScore(toDouble(report.getProblemSolvingScore()));
        vo.setStrengths(parseStringList(report.getStrengthsJson()));
        vo.setWeaknesses(parseStringList(report.getWeaknessesJson()));
        vo.setSuggestions(parseStringList(report.getSuggestionsJson()));
        vo.setReportText(report.getReportText());
        vo.setCreatedAt(report.getCreatedAt());
        return vo;
    }

    private String defaultTitle(String jobTitle, String resumeName) {
        if (StringUtils.hasText(jobTitle)) {
            return jobTitle + " · 模拟面试";
        }
        if (StringUtils.hasText(resumeName)) {
            return resumeName + " · 模拟面试";
        }
        return "模拟面试";
    }

    /**
     * 白名单取值，不在名单里就退到默认值。
     * <p>
     * 和 {@code resume.optimize} 的 target 不一样（那里是报错）——
     * 这两个字段在创建时<b>总是有默认值</b>，前端不传是正常用法，
     * 所以「没传」和「传错」用同一个兜底路径是合理的。
     * 传错的值会退到默认值，不会写进库里。
     */
    private String enumOrDefault(String value, Set<String> allowed, String defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        String upper = value.trim().toUpperCase();
        return allowed.contains(upper) ? upper : defaultValue;
    }

    /**
     * 列表筛选用的状态，非法值直接报错。
     * <p>
     * 这里和 {@link #enumOrDefault} 相反：筛选条件传错说明前端取值和这里对不上，
     * 静默忽略会返回「全部」—— 用户以为筛过了，其实是没筛。报错更容易发现。
     */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String upper = status.trim().toUpperCase();
        if (!STATUSES.contains(upper)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的状态：" + status);
        }
        return upper;
    }

    private int clampQuestionCount(Integer value) {
        if (value == null) {
            return DEFAULT_QUESTION_COUNT;
        }
        // DTO 上的 @Min/@Max 已经挡了，这里再夹一次是为了防「绕过校验直接调 service」
        return Math.min(Math.max(value, 1), MAX_QUESTION_COUNT);
    }

    /** 字符串 id → Long。前端传的 id 是字符串（避免 JS 精度丢失），格式不对按参数错误报 */
    private Long parseId(String value, String field) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "格式不正确：" + value);
        }
    }

    /** JSON 数组字符串 → List。解析不了返回空列表（不抛异常，让页面还能打开） */
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

    private JsonNode parseJsonNode(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("JSON 解析失败，按空处理：{}", e.getMessage());
            return null;
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
     * 后者会把 82.5 变成 82.50000000000000710542735760100185871124267578125。
     * 列是 decimal(6,2)，写进去再读出来就不是原来那个数了。
     */
    private BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 一场面试的作答进度（列表用，一次查出来在内存里聚合，避免 N+1） */
    private record Progress(int total, int answered, BigDecimal scoreSum) {

        /** 平均分，保留两位；一道都没答时返回 null（不是 0 —— 0 分和没答是两回事） */
        Double average() {
            if (answered == 0) {
                return null;
            }
            return scoreSum.divide(BigDecimal.valueOf(answered), 2, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }
}
