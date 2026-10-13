package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.client.AiServiceClient.ResumeAnalyzeData;
import com.zest.mindforgeai.client.AiServiceClient.ResumeAnalyzeRequest;
import com.zest.mindforgeai.client.AiServiceClient.ResumeParseData;
import com.zest.mindforgeai.client.AiServiceClient.ResumeParseRequest;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.UploadConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.StorageProperties;
import com.zest.mindforgeai.mapper.ResumeMapper;
import com.zest.mindforgeai.mapper.ResumeJobAnalysisMapper;
import com.zest.mindforgeai.mapper.ResumeSkillMapper;
import com.zest.mindforgeai.pojo.dto.ResumeOptimizeDTO;
import com.zest.mindforgeai.pojo.entity.Resume;
import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import com.zest.mindforgeai.pojo.entity.ResumeSkill;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeOptimizeVO;
import com.zest.mindforgeai.pojo.vo.ResumeParsedVO;
import com.zest.mindforgeai.pojo.vo.ResumeVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.service.JobService;
import com.zest.mindforgeai.service.KnowledgeBaseService;
import com.zest.mindforgeai.service.ResumeService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 简历服务实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class ResumeServiceImpl extends ServiceImpl<ResumeMapper, Resume> implements ResumeService {

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    private static final String PENDING = "PENDING";
    private static final String PARSING = "PARSING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";

    /** 与 resume.name varchar(100) / target_position varchar(128) 对齐 */
    private static final int MAX_NAME_LEN = 100;
    private static final int MAX_TARGET_POSITION_LEN = 128;

    /** 与 resume.error_message varchar(500) 对齐 */
    private static final int MAX_ERROR_LEN = 500;

    /** 优化时允许指定的分析范围，对齐 Python 侧 {@code resume._ANALYZE_TARGETS} */
    private static final Set<String> ANALYZE_TARGETS = Set.of(
            "SUMMARY", "WORK_EXPERIENCE", "PROJECT_EXPERIENCE",
            "SKILLS", "EDUCATION", "OVERALL");

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。 */
    private static final Map<String, SFunction<Resume, ?>> SORTABLE = Map.of(
            "name", Resume::getName,
            "targetPosition", Resume::getTargetPosition,
            "createdAt", Resume::getCreatedAt,
            "updatedAt", Resume::getUpdatedAt);

    private final FileService fileService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final StorageProperties storageProperties;
    private final AiServiceClient aiServiceClient;
    private final ResumeSkillMapper resumeSkillMapper;
    private final ResumeJobAnalysisMapper resumeJobAnalysisMapper;
    private final JobService jobService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final TransactionTemplate transactionTemplate;

    public ResumeServiceImpl(FileService fileService,
                             AuditService auditService,
                             ObjectMapper objectMapper,
                             StorageProperties storageProperties,
                             AiServiceClient aiServiceClient,
                             ResumeSkillMapper resumeSkillMapper,
                             ResumeJobAnalysisMapper resumeJobAnalysisMapper,
                             JobService jobService,
                             KnowledgeBaseService knowledgeBaseService,
                             TransactionTemplate transactionTemplate) {
        this.fileService = fileService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.storageProperties = storageProperties;
        this.aiServiceClient = aiServiceClient;
        this.resumeSkillMapper = resumeSkillMapper;
        this.resumeJobAnalysisMapper = resumeJobAnalysisMapper;
        this.jobService = jobService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public PageResultVO<ResumeVO> pageResumes(long page, long pageSize, String keyword,
                                             String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        String safeKeyword = SortSupport.escapeLike(keyword);
        LambdaQueryWrapper<Resume> wrapper = new LambdaQueryWrapper<Resume>()
                .eq(Resume::getUserId, SecurityUtils.getUserId())
                // 关键词：简历名称 / 目标职位 任一匹配
                .and(StringUtils.hasText(safeKeyword), w -> w
                        .like(Resume::getName, safeKeyword).or()
                        .like(Resume::getTargetPosition, safeKeyword));

        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, Resume::getId, false);

        Page<Resume> result = page(new Page<>(safePage, safeSize), wrapper);

        // 一次性查出整页的文件记录，避免逐行查造成 N+1
        Map<Long, SysFile> fileMap = loadFiles(
                result.getRecords().stream().map(Resume::getFileId).toList());

        List<ResumeVO> records = result.getRecords().stream()
                .map(r -> ResumeVO.of(r, fileMap.get(r.getFileId())))
                .toList();

        return PageResultVO.of(result, records);
    }

    @Override
    public ResumeDetailVO getDetail(Long id) {
        Resume resume = requireOwned(id);
        return ResumeDetailVO.of(resume, loadFile(resume.getFileId()),
                parseStructuredJson(resume.getStructuredJson()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResumeVO upload(MultipartFile file, String name, String targetPosition) {
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }
        if (!UploadConstants.isAllowed(originalName, UploadConstants.RESUME_EXT)) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "只支持 PDF / DOCX / TXT / Markdown / HTML");
        }

        // 名称：不传就用原文件名。两者都可能超过 varchar(100)，
        // 超了直接截断 —— 简历名称只是给人看的标签，为它报错不值当。
        String resumeName = StringUtils.hasText(name) ? name : originalName;
        if (resumeName.length() > MAX_NAME_LEN) {
            resumeName = resumeName.substring(0, MAX_NAME_LEN);
        }
        if (targetPosition != null && targetPosition.length() > MAX_TARGET_POSITION_LEN) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标职位最长 128 个字符");
        }

        // 上传对象存储 + 落 sys_file（内含秒传）
        SysFile sysFile = fileService.upload(file, FileService.BIZ_RESUME);

        Resume resume = new Resume()
                .setUserId(SecurityUtils.getUserId())
                .setFileId(sysFile.getId())
                .setName(resumeName)
                .setTargetPosition(targetPosition)
                // 上传只负责把文件存下来。文本提取和结构化要用户显式触发解析
                // （POST /resumes/{id}/parse）—— 那是个几十秒的慢接口，
                // 塞进上传请求里会让「上传」这个动作变得不可预期。
                .setParseStatus(PENDING)
                .setStatus(1);
        save(resume);

        log.info("简历上传成功 resumeId={} name={} fileName={} size={}",
                resume.getId(), resumeName, originalName, sysFile.getFileSize());

        auditService.recordSuccess(AuditConstants.ACTION_RESUME_CREATE,
                AuditConstants.RESOURCE_RESUME, String.valueOf(resume.getId()),
                Map.of("name", resumeName,
                       "fileName", originalName,
                       "fileSize", sysFile.getFileSize()));

        return ResumeVO.of(resume, sysFile);
    }

    @Override
    public ResumeParsedVO getParsed(Long id) {
        Resume resume = requireOwned(id);
        return ResumeParsedVO.of(resume, parseStructuredJson(resume.getStructuredJson()));
    }

    @Override
    public ResumeOptimizeVO optimize(Long id, ResumeOptimizeDTO dto) {
        Resume resume = requireOwned(id);

        // 优化是「读原文 → 给建议」，所以原文必须先有 —— 它由解析产生。
        // ⚠️ 这里【不自动触发解析】：解析是几十秒的慢接口，偷偷串进来会让
        //    这个请求的耗时变得完全不可预期（用户点「优化」却等了两分半），
        //    而且失败时该报哪个错也说不清了。
        if (!StringUtils.hasText(resume.getRawContent())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "这份简历还没有解析过，请先解析再优化");
        }

        String target = normalizeTarget(dto == null ? null : dto.getTarget());
        String jobText = resolveJobText(dto == null ? null : dto.getJobId());
        String systemKbIds = knowledgeBaseService.resolveDualTrackKbIds(null);

        ResumeAnalyzeData data = aiServiceClient.analyzeResume(new ResumeAnalyzeRequest(
                String.valueOf(SecurityUtils.getUserId()),
                String.valueOf(id),
                resume.getRawContent(),
                jobText,
                target,
                systemKbIds));

        ResumeOptimizeVO vo = toOptimizeVO(id, data);

        log.info("简历优化 resumeId={} 建议={} 条（待补事实 {} 条）对照JD={}",
                id, vo.getItems().size(), vo.getNeedsFactCount(),
                StringUtils.hasText(jobText));

        // 虽然一行业务数据都不写，但它烧了 token —— 留痕是为了能回答
        // 「这个月的 AI 调用是谁点的」（同简历解析）。
        auditService.recordSuccess(AuditConstants.ACTION_RESUME_OPTIMIZE,
                AuditConstants.RESOURCE_RESUME, String.valueOf(id),
                Map.of("itemCount", vo.getItems().size(),
                       "needsFactCount", vo.getNeedsFactCount(),
                       "withJob", StringUtils.hasText(jobText)));

        return vo;
    }

    @Override
    public ResumeParsedVO parse(Long id) {
        Resume resume = requireOwned(id);

        if (resume.getFileId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "这份简历没有原始文件，无法解析");
        }
        // 并发保护：解析要几十秒，这期间再点一次会起第二个 AI 调用 ——
        // 两次结果互相覆盖，而且白烧一遍 token。
        if (PARSING.equals(resume.getParseStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "简历正在解析中，请稍候");
        }

        SysFile file = loadFile(resume.getFileId());
        if (file == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "简历的原始文件记录已不存在");
        }

        // Python 侧的 parse() 按扩展名大写匹配（TXT / MD / MARKDOWN / PDF / DOCX / HTML / HTM）
        String fileType = UploadConstants.extensionOf(file.getOriginalName()).toUpperCase();
        if (!UploadConstants.RESUME_EXT.contains(fileType.toLowerCase())) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "不支持的文件类型：" + fileType);
        }

        // ★ 先标 PARSING 并【独立提交】。
        //   这一步必须自己提交，不能和结果写在同一个事务里 ——
        //   整段解析要几十秒，共用一个事务的话 PARSING 对外永远不可见（未提交），
        //   前端轮询只会一直看到 PENDING，还以为没开始。
        markParseStatus(id, PARSING, null);

        try {
            ResumeParseData data = aiServiceClient.parseResume(new ResumeParseRequest(
                    String.valueOf(SecurityUtils.getUserId()),
                    String.valueOf(id),
                    // OSS 预签名地址由 Java 签发，Python 侧不需要任何 OSS 密钥
                    fileService.downloadUrl(file),
                    fileType,
                    resume.getTargetPosition()));

            persistParsed(id, data);

            log.info("简历解析成功 resumeId={} 技能={} 项 文本截断={}",
                    id, data.skills() == null ? 0 : data.skills().size(),
                    Boolean.TRUE.equals(data.truncated()));

            auditService.recordSuccess(AuditConstants.ACTION_RESUME_PARSE,
                    AuditConstants.RESOURCE_RESUME, String.valueOf(id),
                    Map.of("skills", data.skills() == null ? 0 : data.skills().size(),
                           "truncated", Boolean.TRUE.equals(data.truncated())));

        } catch (RuntimeException e) {
            // 失败也要留痕：把原因写进 error_message，前端才能说出「为什么失败」，
            // 而不是只显示一个 FAILED。写完【照常抛出】——
            // 这次请求本身也是失败的，调用方必须知道。
            //
            // ⚠️ 刻意【不动】raw_content / structured_json / resume_skill：
            //    正文没变，上一次成功的结果依然是对的。
            //    因为一次重试失败就把用户已有的解析结果删掉，代价太大。
            //    所以 parseStatus 是「最近一次尝试」，structuredJson 是「最近一次成功」。
            markParseStatus(id, FAILED, truncateError(e.getMessage()));
            log.error("简历解析失败 resumeId={}", id, e);
            throw e;
        }

        return getParsed(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Resume resume = requireOwned(id);

        // ① 逻辑删除 resume（MP 自动设 deleted = 1）
        removeById(id);

        // ② 技能和匹配分析【物理删】—— 这两张表都没有 deleted 列。
        //    本项目所有表都没有外键，所以必须手动清，而且要和删除写在同一个事务里
        //    （清一半会留下孤儿数据）。匹配分析引用着 resumeId，简历没了它就没意义了。
        int skills = resumeSkillMapper.delete(new LambdaQueryWrapper<ResumeSkill>()
                .eq(ResumeSkill::getResumeId, id));
        int analyses = resumeJobAnalysisMapper.delete(new LambdaQueryWrapper<ResumeJobAnalysis>()
                .eq(ResumeJobAnalysis::getResumeId, id));

        // ③ 逻辑删除 sys_file —— 必须走 softDelete 而不是 removeById，
        //    因为清理任务靠 deleted_at 算恢复窗口，MP 不会写这个字段
        SysFile sysFile = loadFile(resume.getFileId());
        if (sysFile != null) {
            fileService.softDelete(sysFile);
        }

        // ⚠️ 不动 OSS 对象 —— 留恢复窗口，到期由 FilePurgeTask 判断引用计数后清理
        int retentionDays = storageProperties.getRetentionDays();
        log.info("删除简历 resumeId={} name={}（连带清掉 {} 项技能、{} 条匹配分析；OSS 对象保留 {} 天）",
                id, resume.getName(), skills, analyses, retentionDays);

        auditService.recordSuccess(AuditConstants.ACTION_RESUME_DELETE,
                AuditConstants.RESOURCE_RESUME, String.valueOf(id),
                Map.of("name", resume.getName(),
                       "ossRetentionDays", retentionDays));
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 取简历并校验归属。
     * <p>
     * 不存在、或者存在但不属于当前用户，都抛 {@code RESUME_NOT_FOUND} ——
     * <b>刻意不区分</b>：抛 403 等于告诉调用方「这个 id 存在，只是不归你」，
     * 相当于帮他确认了别人的数据范围。抛 404 什么都不泄漏。
     */
    private Resume requireOwned(Long id) {
        Resume resume = getById(id);
        if (resume == null || !resume.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.RESUME_NOT_FOUND);
        }
        return resume;
    }

    /** 单个文件记录，id 为空或查不到都返回 null */
    private SysFile loadFile(Long fileId) {
        return fileId == null ? null : fileService.getById(fileId);
    }

    /** 批量查文件记录，返回 fileId → SysFile 的映射 */
    private Map<Long, SysFile> loadFiles(List<Long> fileIds) {
        List<Long> ids = fileIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return fileService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysFile::getId, Function.identity(), (a, b) -> a));
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
     * <b>为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}</b>：
     * <ul>
     *   <li>整个 {@link #parse} 不能包事务 —— AI 调用要几十秒，
     *       事务会一直占着数据库连接（连接池会被拖垮）</li>
     *   <li>但「更新 resume + 重写技能」必须原子 —— 只成一半会留下
     *       「有结构化数据、技能表却是空的」这种自相矛盾的状态</li>
     *   <li>而在类内部直接调 {@code this.xxx()} 上的 {@code @Transactional}
     *       不走 Spring 代理、不生效，所以这里显式用 TransactionTemplate</li>
     * </ul>
     */
    private void persistParsed(Long id, ResumeParseData data) {
        String structuredJson = data.structured() == null ? null : data.structured().toString();

        transactionTemplate.executeWithoutResult(tx -> {
            // ⚠️ 用 LambdaUpdateWrapper 而不是 updateById：
            //    updateById 默认忽略 null 字段，清不掉上一次失败留下的 error_message
            update(new LambdaUpdateWrapper<Resume>()
                    .eq(Resume::getId, id)
                    .set(Resume::getRawContent, data.rawText())
                    .set(Resume::getSummary, data.summary())
                    .set(Resume::getStructuredJson, structuredJson)
                    .set(Resume::getParseStatus, SUCCESS)
                    .set(Resume::getErrorMessage, null)
                    .set(Resume::getUpdatedAt, LocalDateTime.now()));

            // 技能：全删全插。resume_skill 没有 deleted 列，只能物理删。
            // 重新解析时旧技能必须清掉 —— 否则「上次识别出、这次没识别出」的技能
            // 会一直留在表里，后面按技能匹配 JD 时会多算出匹配项。
            resumeSkillMapper.delete(new LambdaQueryWrapper<ResumeSkill>()
                    .eq(ResumeSkill::getResumeId, id));

            for (ResumeParseData.Skill skill : nullToEmpty(data.skills())) {
                if (!StringUtils.hasText(skill.name())) {
                    continue;
                }
                resumeSkillMapper.insert(new ResumeSkill()
                        .setResumeId(id)
                        .setSkillName(skill.name())
                        .setSkillType(skill.type())
                        .setProficiency(skill.proficiency())
                        .setYearsExperience(toDecimal(skill.years())));
            }
        });
    }

    /**
     * 只改解析状态（和失败原因）。
     * <p>
     * ⚠️ 刻意<b>不加 {@code @Transactional}</b>：它要立刻对外可见。
     * {@code PARSING} 是给前端轮询看的中间态，包进事务就等于没写。
     */
    private void markParseStatus(Long id, String status, String errorMessage) {
        update(new LambdaUpdateWrapper<Resume>()
                .eq(Resume::getId, id)
                .set(Resume::getParseStatus, status)
                .set(Resume::getErrorMessage, errorMessage)
                .set(Resume::getUpdatedAt, LocalDateTime.now()));
    }

    /** 失败原因截断到列宽。完整信息在服务日志里，这里只留给人看的一句 */
    private String truncateError(String message) {
        if (!StringUtils.hasText(message)) {
            return "解析失败（上游未提供原因）";
        }
        return message.length() > MAX_ERROR_LEN ? message.substring(0, MAX_ERROR_LEN) : message;
    }

    /**
     * Double → BigDecimal。
     * <p>
     * 用 {@code valueOf} 而不是 {@code new BigDecimal(double)} ——
     * 后者会把 3.5 变成 3.500000000000000444089209850062616169452667236328125。
     * 列是 decimal(4,1)，写进去再读出来就不是原来那个数了。
     */
    private BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * 校验并归一化分析范围。
     * <p>
     * 不在白名单里的值<b>报错</b>而不是静默忽略 —— 这个参数是用户在界面上选的，
     * 传错了说明前端和这里的取值对不上。静默按「整体」处理会让这个不一致一直藏着。
     */
    private String normalizeTarget(String target) {
        if (!StringUtils.hasText(target)) {
            return null;        // 空 = 整体，交给 Python 走它自己的默认
        }
        String upper = target.trim().toUpperCase();
        if (!ANALYZE_TARGETS.contains(upper)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的分析范围：" + target);
        }
        return upper;
    }

    /**
     * 取目标岗位的 JD 原文，没传 jobId 就返回 null（= 按通用标准评）。
     * <p>
     * 走 {@link JobService#getDetail(Long)} 而不是直接查库：归属校验
     * （不是自己的 → 404）在那边已经做好了，这里再来一遍既重复又容易漏。
     */
    private String resolveJobText(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return null;
        }
        JobDetailVO job = jobService.getDetail(parseId(jobId));
        if (!StringUtils.hasText(job.getRawContent())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "目标职位还没有解析过，请先解析再优化");
        }
        return job.getRawContent();
    }

    /** 前端传的 id 是字符串（避免 JS 精度丢失），格式不对按参数错误报而不是 500 */
    private Long parseId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "id 格式不正确：" + value);
        }
    }

    /** AI 返回的报文 → 对外 VO */
    private ResumeOptimizeVO toOptimizeVO(Long resumeId, ResumeAnalyzeData data) {
        List<ResumeOptimizeVO.OptimizeItem> items = nullToEmpty(data.items()).stream()
                .map(src -> {
                    boolean needsFact = Boolean.TRUE.equals(src.needsFact());

                    ResumeOptimizeVO.OptimizeItem item = new ResumeOptimizeVO.OptimizeItem();
                    item.setTarget(src.target());
                    item.setOriginal(src.original());
                    item.setIssue(src.issue());
                    item.setSuggestion(src.suggestion());
                    // ★ 再兜一道：needsFact 为真时绝不把 rewritten 透出去。
                    //   Python 侧已经强制清空了（resume.py 的 _normalize_analysis），
                    //   这里再挡一次是因为代价不对称 —— 多写两行 vs.
                    //   让用户拿到一段编造的经历、面试时被追问两句就露馅。
                    item.setRewritten(needsFact ? null : src.rewritten());
                    item.setNeedsFact(needsFact);
                    item.setMissingFact(needsFact ? src.missingFact() : null);
                    return item;
                })
                .toList();

        ResumeOptimizeVO vo = new ResumeOptimizeVO();
        vo.setResumeId(String.valueOf(resumeId));
        vo.setOverallScore(data.overallScore());
        vo.setSummary(data.summary());
        vo.setHighlights(nullToEmpty(data.highlights()));
        vo.setItems(items);
        vo.setNeedsFactCount((int) items.stream()
                .filter(i -> Boolean.TRUE.equals(i.getNeedsFact()))
                .count());
        vo.setTruncated(data.truncated());
        return vo;
    }
}
