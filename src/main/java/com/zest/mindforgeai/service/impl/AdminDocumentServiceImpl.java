package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.UploadConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.StorageProperties;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.mapper.KnowledgeBaseMapper;
import com.zest.mindforgeai.pojo.dto.DocumentStatusUpdateDTO;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.entity.KnowledgeBase;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.pojo.vo.AdminDocumentVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AdminDocumentService;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.utils.DocumentStatusUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端文档管理实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class AdminDocumentServiceImpl implements AdminDocumentService {

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    /*
     * 三个字段各自允许的取值。
     *
     * 为什么在管理端要校验：这个接口是【人】在点按钮，写错一个字母
     * （比如 "SUCESS"）就会往库里塞一个谁也认不出的状态 ——
     * 聚合函数会把它算成 UPLOADED，Python 的流水线也会判断不出来，
     * 表现为「文档莫名其妙不动了」，而根因在一个拼写错误上。
     * 内部接口（Python 调用）不校验，因为那是程序写的，写错是代码 bug，
     * 应该由测试发现而不是靠运行时拦。
     */
    private static final Set<String> ALLOWED_PARSE =
            Set.of(DocumentStatusUtils.PENDING, DocumentStatusUtils.PARSING,
                    DocumentStatusUtils.SUCCESS, DocumentStatusUtils.FAILED);
    private static final Set<String> ALLOWED_CHUNK =
            Set.of(DocumentStatusUtils.PENDING, "CHUNKING",
                    DocumentStatusUtils.SUCCESS, DocumentStatusUtils.FAILED);
    private static final Set<String> ALLOWED_VECTOR =
            Set.of(DocumentStatusUtils.PENDING, "EMBEDDING",
                    DocumentStatusUtils.SUCCESS, DocumentStatusUtils.FAILED);

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。
     *  值是【数据库列名】—— 这几个走自定义 XML 分页，排序由 MP 拼进 SQL。 */
    private static final Map<String, String> SORTABLE = Map.of(
            "fileName", "d.file_name",
            "fileSize", "d.file_size",
            "createdAt", "d.created_at",
            "updatedAt", "d.updated_at");

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileService fileService;
    private final AuditService auditService;
    private final StorageProperties storageProperties;
    private final AiServiceClient aiServiceClient;
    /** 手动事务，用来把「只有落库」的那几步收成原子（见 importUrl 的注释） */
    private final TransactionTemplate transactionTemplate;

    public AdminDocumentServiceImpl(DocumentMapper documentMapper,
                                    KnowledgeBaseMapper knowledgeBaseMapper,
                                    FileService fileService,
                                    AuditService auditService,
                                    StorageProperties storageProperties,
                                    AiServiceClient aiServiceClient,
                                    TransactionTemplate transactionTemplate) {
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.fileService = fileService;
        this.auditService = auditService;
        this.storageProperties = storageProperties;
        this.aiServiceClient = aiServiceClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public PageResultVO<AdminDocumentVO> pageDocuments(long page, long pageSize, String keyword,
                                                       Long knowledgeBaseId, Long userId, String status,
                                                       String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        Page<AdminDocumentVO> pageParam = new Page<>(safePage, safeSize);
        // ⚠️ XML 里不能自己写 ORDER BY —— MP 会追加在后面，导致它不生效
        SortSupport.applyTo(pageParam, sortBy, sortOrder, SORTABLE, "d.id", false);

        IPage<AdminDocumentVO> result = documentMapper.selectAdminPage(
                pageParam, knowledgeBaseId, userId, SortSupport.escapeLike(keyword), status);

        // 聚合状态不在 SQL 里算（见 DocumentMapper.xml 的说明），映射完在这里补
        result.getRecords().forEach(this::fillAggregateStatus);

        return PageResultVO.of(result, result.getRecords());
    }

    @Override
    public AdminDocumentVO getDetail(Long id) {
        AdminDocumentVO vo = documentMapper.selectAdminById(id);
        if (vo == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        fillAggregateStatus(vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminDocumentVO upload(Long knowledgeBaseId, MultipartFile file, String description) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (kb == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }
        String ext = UploadConstants.extensionOf(originalName);
        if (!UploadConstants.isAllowed(originalName, UploadConstants.DOCUMENT_EXT)) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "只支持 PDF / DOCX / TXT / Markdown / HTML");
        }

        SysFile sysFile = fileService.upload(file, FileService.BIZ_DOCUMENT);

        Document doc = new Document()
                .setUserId(kb.getUserId())
                .setKnowledgeBaseId(knowledgeBaseId)
                .setFileId(sysFile.getId())
                .setFileName(originalName)
                .setFileType(ext.toUpperCase())
                .setFileUrl(sysFile.getObjectKey())
                .setFileSize(sysFile.getFileSize())
                .setContentHash(sysFile.getContentHash())
                .setParseStatus(DocumentStatusUtils.PENDING)
                .setChunkStatus(DocumentStatusUtils.PENDING)
                .setVectorStatus(DocumentStatusUtils.PENDING);
        documentMapper.insert(doc);

        log.info("管理端上传文档成功 documentId={} kbId={} fileName={} size={}",
                doc.getId(), knowledgeBaseId, originalName, sysFile.getFileSize());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("knowledgeBaseId", knowledgeBaseId);
        detail.put("fileName", originalName);
        detail.put("fileSize", sysFile.getFileSize());
        detail.put("description", description == null ? "" : description);
        detail.put("byAdmin", true);

        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_UPLOAD,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(doc.getId()), detail);

        return getDetail(doc.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminDocumentVO importUrl(Long knowledgeBaseId, String url) {
        if (!StringUtils.hasText(url) || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "URL 格式不合法，必须以 http:// 或 https:// 开头");
        }

        KnowledgeBase kb = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (kb == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        log.info("管理端开始抓取网页导入知识库 kbId={} url={}", knowledgeBaseId, url);
        AiServiceClient.UrlExtractData extractData = aiServiceClient.extractUrl(url);
        if (extractData == null || !StringUtils.hasText(extractData.content())) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "未能从指定 URL 提取到有效正文");
        }

        String rawTitle = StringUtils.hasText(extractData.title()) ? extractData.title().trim() : "web_article";
        String cleanTitle = rawTitle.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (cleanTitle.length() > 80) {
            cleanTitle = cleanTitle.substring(0, 80);
        }
        String fileName = cleanTitle + ".md";

        String mdContent = String.format("# %s\n\n> 来源网页: %s\n\n%s\n",
                rawTitle, url.trim(), extractData.content());
        byte[] bytes = mdContent.getBytes(StandardCharsets.UTF_8);

        SysFile sysFile = fileService.uploadBytes(bytes, fileName, "text/markdown;charset=UTF-8", FileService.BIZ_DOCUMENT);

        // 只有落库这一步需要原子性 —— 抓取网页和上传 OSS 都要几十秒，
        // 包进事务会一直占着数据库连接，几个管理员同时导入就能把连接池耗光。
        // （项目红线：AI/网络调用绝不包事务）
        Long documentId = transactionTemplate.execute(status -> {
            Document doc = new Document()
                    .setUserId(kb.getUserId())
                    .setKnowledgeBaseId(knowledgeBaseId)
                    .setFileId(sysFile.getId())
                    .setFileName(fileName)
                    .setFileType("MD")
                    .setFileUrl(sysFile.getObjectKey())
                    .setFileSize(sysFile.getFileSize())
                    .setContentHash(sysFile.getContentHash())
                    .setParseStatus(DocumentStatusUtils.PENDING)
                    .setChunkStatus(DocumentStatusUtils.PENDING)
                    .setVectorStatus(DocumentStatusUtils.PENDING);
            documentMapper.insert(doc);
            return doc.getId();
        });

        log.info("管理端网页导入文档成功 documentId={} kbId={} fileName={} charCount={}",
                documentId, knowledgeBaseId, fileName, extractData.charCount());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("knowledgeBaseId", knowledgeBaseId);
        detail.put("url", url);
        detail.put("fileName", fileName);
        detail.put("fileSize", sysFile.getFileSize());
        detail.put("charCount", extractData.charCount());
        detail.put("byAdmin", true);

        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_UPLOAD,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(documentId), detail);

        return getDetail(documentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, DocumentStatusUpdateDTO dto) {
        Document doc = requireExists(id);

        validateStatus("解析状态", dto.getParseStatus(), ALLOWED_PARSE);
        validateStatus("分块状态", dto.getChunkStatus(), ALLOWED_CHUNK);
        validateStatus("向量化状态", dto.getVectorStatus(), ALLOWED_VECTOR);

        // 一个字段都没传就是空操作，直接报错 —— 比静默返回 200 好：
        // 管理员会以为改成功了，实际什么都没变
        if (dto.getParseStatus() == null && dto.getChunkStatus() == null
                && dto.getVectorStatus() == null && dto.getErrorMessage() == null
                && dto.getPageCount() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少要指定一个要修改的字段");
        }

        String before = DocumentStatusUtils.aggregate(doc);

        // 只 set 有值的字段 —— null 表示「这个阶段还没到，别动它」
        LambdaUpdateWrapper<Document> wrapper = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .set(dto.getParseStatus() != null,
                        Document::getParseStatus, dto.getParseStatus())
                .set(dto.getChunkStatus() != null,
                        Document::getChunkStatus, dto.getChunkStatus())
                .set(dto.getVectorStatus() != null,
                        Document::getVectorStatus, dto.getVectorStatus())
                .set(dto.getPageCount() != null, Document::getPageCount, dto.getPageCount())
                .set(Document::getUpdatedAt, LocalDateTime.now());

        // errorMessage 特殊：只要不是 null 就写（空字符串用来「清空上次的错误」）
        if (dto.getErrorMessage() != null) {
            wrapper.set(Document::getErrorMessage, dto.getErrorMessage());
        }

        documentMapper.update(null, wrapper);

        // 只打「改了哪些字段」—— 这里没有重新查库，算不出新的聚合状态，
        // 与其打一个假的 before -> after，不如如实写原状态 + 目标值。
        // 完整的 before/after 在下面的审计 detail 里。
        log.warn("管理员修改文档状态 documentId={} fileName={}（原聚合状态 {}）；"
                        + "parse={} chunk={} vector={} pageCount={} error={}",
                id, doc.getFileName(), before,
                dto.getParseStatus(), dto.getChunkStatus(), dto.getVectorStatus(),
                dto.getPageCount(), dto.getErrorMessage());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fileName", doc.getFileName());
        detail.put("beforeStatus", before);
        detail.put("parseStatus", dto.getParseStatus());
        detail.put("chunkStatus", dto.getChunkStatus());
        detail.put("vectorStatus", dto.getVectorStatus());
        detail.put("pageCount", dto.getPageCount());
        detail.put("errorMessage", dto.getErrorMessage());
        detail.put("byAdmin", true);

        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_UPDATE,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(id), detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long id) {
        Document doc = requireExists(id);

        // 只有处理失败的才能重试。
        // 允许重试「处理中」的文档会让 AI 服务对同一份文档跑两遍，状态互相覆盖。
        if (!DocumentStatusUtils.FAILED.equals(doc.getParseStatus())
                && !DocumentStatusUtils.FAILED.equals(doc.getChunkStatus())
                && !DocumentStatusUtils.FAILED.equals(doc.getVectorStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "只有处理失败的文档可以重试，当前状态："
                            + DocumentStatusUtils.aggregate(doc));
        }

        String previousStatus = DocumentStatusUtils.aggregate(doc);

        // 三个状态一起重置，并清掉上次的错误信息。
        // 用 LambdaUpdateWrapper 而不是 updateById：MP 的 updateById 默认忽略 null 字段，
        // 传 null 的 errorMessage 根本不会被写进 SQL，旧错误信息会残留。
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .set(Document::getParseStatus, DocumentStatusUtils.PENDING)
                .set(Document::getChunkStatus, DocumentStatusUtils.PENDING)
                .set(Document::getVectorStatus, DocumentStatusUtils.PENDING)
                .set(Document::getErrorMessage, null)
                .set(Document::getUpdatedAt, LocalDateTime.now()));

        log.info("管理员重试文档 documentId={} fileName={}，状态已重置为 PENDING",
                id, doc.getFileName());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fileName", doc.getFileName());
        detail.put("knowledgeBaseId", doc.getKnowledgeBaseId());
        detail.put("previousStatus", previousStatus);
        detail.put("byAdmin", true);

        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_RETRY,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(id), detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Document doc = requireExists(id);

        // ① 逻辑删除 document（MP 自动设 deleted = 1）
        documentMapper.deleteById(id);

        // ② 逻辑删除 sys_file —— 必须走 softDelete 而不是 deleteById，
        //    因为清理任务靠 deleted_at 算恢复窗口，MP 不会写这个字段
        if (doc.getFileId() != null) {
            SysFile sysFile = fileService.getById(doc.getFileId());
            if (sysFile != null) {
                fileService.softDelete(sysFile);
            }
        }

        // ⚠️ 不动 OSS 对象 —— 留恢复窗口，到期由 FilePurgeTask 判断引用计数后清理
        int retentionDays = storageProperties.getRetentionDays();
        log.info("管理员删除文档 documentId={} fileName={}（OSS 对象保留 {} 天）",
                id, doc.getFileName(), retentionDays);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fileName", doc.getFileName());
        detail.put("knowledgeBaseId", doc.getKnowledgeBaseId());
        detail.put("ownerId", doc.getUserId());
        detail.put("ossRetentionDays", retentionDays);
        detail.put("byAdmin", true);

        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_DELETE,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(id), detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchRetry(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            Document doc = documentMapper.selectById(id);
            if (doc == null) {
                continue;
            }
            if (DocumentStatusUtils.FAILED.equals(doc.getParseStatus())
                    || DocumentStatusUtils.FAILED.equals(doc.getChunkStatus())
                    || DocumentStatusUtils.FAILED.equals(doc.getVectorStatus())) {
                retry(id);
                count++;
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            Document doc = documentMapper.selectById(id);
            if (doc != null) {
                delete(id);
                count++;
            }
        }
        return count;
    }

    /* ---------- 内部工具 ---------- */

    private Document requireExists(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return doc;
    }

    private void validateStatus(String fieldLabel, String value, Set<String> allowed) {
        if (StringUtils.hasText(value) && !allowed.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    fieldLabel + "取值不合法，允许：" + allowed);
        }
    }

    /** 补上聚合后的总状态（SQL 里不算，见 DocumentMapper.xml 的说明） */
    private void fillAggregateStatus(AdminDocumentVO vo) {
        vo.setStatus(DocumentStatusUtils.aggregate(
                vo.getParseStatus(), vo.getChunkStatus(), vo.getVectorStatus()));
    }
}
