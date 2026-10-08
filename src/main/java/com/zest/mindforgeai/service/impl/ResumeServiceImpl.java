package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.UploadConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.StorageProperties;
import com.zest.mindforgeai.mapper.ResumeMapper;
import com.zest.mindforgeai.pojo.entity.Resume;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeParsedVO;
import com.zest.mindforgeai.pojo.vo.ResumeVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.service.ResumeService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    /** 与 resume.name varchar(100) / target_position varchar(128) 对齐 */
    private static final int MAX_NAME_LEN = 100;
    private static final int MAX_TARGET_POSITION_LEN = 128;

    private final FileService fileService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final StorageProperties storageProperties;

    public ResumeServiceImpl(FileService fileService,
                             AuditService auditService,
                             ObjectMapper objectMapper,
                             StorageProperties storageProperties) {
        this.fileService = fileService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.storageProperties = storageProperties;
    }

    @Override
    public PageResultVO<ResumeVO> pageResumes(long page, long pageSize, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Resume> wrapper = new LambdaQueryWrapper<Resume>()
                .eq(Resume::getUserId, SecurityUtils.getUserId())
                // 关键词：简历名称 / 目标职位 任一匹配
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Resume::getName, keyword).or()
                        .like(Resume::getTargetPosition, keyword))
                .orderByDesc(Resume::getId);

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
                // 文本提取和结构化都还没做 —— 等 Python AI 服务
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
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Resume resume = requireOwned(id);

        // ① 逻辑删除 resume（MP 自动设 deleted = 1）
        removeById(id);

        // ② 逻辑删除 sys_file —— 必须走 softDelete 而不是 removeById，
        //    因为清理任务靠 deleted_at 算恢复窗口，MP 不会写这个字段
        SysFile sysFile = loadFile(resume.getFileId());
        if (sysFile != null) {
            fileService.softDelete(sysFile);
        }

        // ⚠️ 不动 OSS 对象 —— 留恢复窗口，到期由 FilePurgeTask 判断引用计数后清理
        int retentionDays = storageProperties.getRetentionDays();
        log.info("删除简历 resumeId={} name={}（OSS 对象保留 {} 天）",
                id, resume.getName(), retentionDays);

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
}
