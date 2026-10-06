package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.StorageProperties;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.entity.KnowledgeBase;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.pojo.vo.DocumentStatusVO;
import com.zest.mindforgeai.pojo.vo.DocumentVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.DocumentService;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.service.KnowledgeBaseService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 文档服务实现
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements DocumentService {

    /** 允许上传的扩展名，对齐文档 §十六：PDF / DOCX / TXT / Markdown / HTML */
    private static final Set<String> ALLOWED_EXT =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    /* 三个处理阶段的状态取值 */
    private static final String PENDING = "PENDING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String PARSING = "PARSING";

    private final KnowledgeBaseService knowledgeBaseService;
    private final FileService fileService;
    private final AuditService auditService;
    private final StorageProperties storageProperties;

    public DocumentServiceImpl(KnowledgeBaseService knowledgeBaseService,
                               FileService fileService,
                               AuditService auditService,
                               StorageProperties storageProperties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.fileService = fileService;
        this.auditService = auditService;
        this.storageProperties = storageProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentVO upload(Long knowledgeBaseId, MultipartFile file, String description) {
        // ① 校验知识库归属 —— 不校验的话，任何人都能往别人的知识库里塞文档
        // 会检验是否与当前登录用户userId一致
        KnowledgeBase kb = knowledgeBaseService.getOwned(knowledgeBaseId);

        // ② 校验文件名与类型
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }
        // 拿到后缀名，文件类型
        String ext = extractExtension(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            // 文件类型错误
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "只支持 PDF / DOCX / TXT / Markdown / HTML");
        }

        // ③ 上传对象存储 + 落 sys_file（内含秒传）
        SysFile sysFile = fileService.upload(file, FileService.BIZ_DOCUMENT);

        // ④ 落 document 记录
        Document doc = new Document()
                .setUserId(kb.getUserId())
                .setKnowledgeBaseId(knowledgeBaseId)
                .setFileId(sysFile.getId())
                .setFileName(originalName)
                .setFileType(ext.toUpperCase())
                // file_url 存 objectKey（持久值），下载时再生成有有效期的预签名 URL ——
                // 直接存预签名 URL 会过期，存不了
                .setFileUrl(sysFile.getObjectKey())
                .setFileSize(sysFile.getFileSize())
                .setContentHash(sysFile.getContentHash())
                .setParseStatus(PENDING)
                .setChunkStatus(PENDING)
                .setVectorStatus(PENDING);
        save(doc);

        log.info("文档上传成功 documentId={} kbId={} fileName={} size={}",
                doc.getId(), knowledgeBaseId, originalName, sysFile.getFileSize());

        // 记录审计日志
        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_UPLOAD,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(doc.getId()),
                Map.of("knowledgeBaseId", knowledgeBaseId,
                       "fileName", originalName,
                       "fileSize", sysFile.getFileSize(),
                       "description", description == null ? "" : description));

        // 转为DocumentVO返回
        return toVO(doc);
    }

    @Override
    public PageResultVO<DocumentVO> pageDocuments(Long knowledgeBaseId, long page, long pageSize,
                                                  String keyword) {
        // 先校验知识库归属 —— 不是你的库，就看不到里面的文档
        knowledgeBaseService.getOwned(knowledgeBaseId);

        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
                .like(StringUtils.hasText(keyword), Document::getFileName, keyword)
                .orderByDesc(Document::getId);

        Page<Document> result = page(new Page<>(safePage, safeSize), wrapper);

        return PageResultVO.of(result,
                result.getRecords().stream().map(this::toVO).toList());
    }

    @Override
    public DocumentVO getDetail(Long documentId) {
        return toVO(getOwned(documentId));
    }

    @Override
    public DocumentStatusVO getStatus(Long documentId) {
        Document d = getOwned(documentId);

        DocumentStatusVO vo = new DocumentStatusVO();
        vo.setDocumentId(String.valueOf(d.getId()));
        vo.setStatus(aggregateStatus(d));
        vo.setParseStatus(d.getParseStatus());
        vo.setChunkStatus(d.getChunkStatus());
        vo.setVectorStatus(d.getVectorStatus());
        vo.setErrorMessage(d.getErrorMessage());
        vo.setUpdatedAt(d.getUpdatedAt());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long documentId) {
        Document doc = getOwned(documentId);

        // 只有处理失败的才能重试。
        // 允许重试「处理中」的文档会让 AI 服务对同一份文档跑两遍，状态互相覆盖。
        if (!FAILED.equals(doc.getParseStatus())
                && !FAILED.equals(doc.getChunkStatus())
                && !FAILED.equals(doc.getVectorStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "只有处理失败的文档可以重试，当前状态：" + aggregateStatus(doc));
        }

        String previousStatus = aggregateStatus(doc);

        // 三个状态一起重置，并清掉上次的错误信息。
        // 用 LambdaUpdateWrapper 而不是 updateById：MP 的 updateById 默认忽略 null 字段，
        // 传 null 的 errorMessage 根本不会被写进 SQL，旧错误信息会残留。
        update(new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .set(Document::getParseStatus, PENDING)
                .set(Document::getChunkStatus, PENDING)
                .set(Document::getVectorStatus, PENDING)
                .set(Document::getErrorMessage, null)
                .set(Document::getUpdatedAt, LocalDateTime.now()));

        // ⚠️ 待补：这里应该调用 Python 的 POST /internal/ai/v1/documents/parse
        //    （文档 §十七）。AI 服务尚未搭建，所以目前只是把状态重置为 PENDING，
        //    等 Python 接上后在这个位置补一次调用即可。
        log.info("重试文档 documentId={} fileName={}，状态已重置为 PENDING（等待 AI 服务处理）",
                documentId, doc.getFileName());

        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_RETRY,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(documentId),
                Map.of("fileName", doc.getFileName(),
                       "previousStatus", previousStatus));
    }

    @Override
    public Document getOwned(Long documentId) {
        Document doc = getById(documentId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (!doc.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN);
        }
        return doc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long documentId) {
        Document doc = getOwned(documentId);

        // ① 逻辑删除 document（MP 自动设 deleted = 1）
        removeById(documentId);

        // ② 逻辑删除 sys_file —— 必须走 softDelete 而不是 removeById，
        //    因为清理任务靠 deleted_at 算恢复窗口，MP 不会写这个字段
        if (doc.getFileId() != null) {
            SysFile sysFile = fileService.getById(doc.getFileId());
            if (sysFile != null) {
                fileService.softDelete(sysFile);
            }
        }

        // ⚠️ 不动 OSS 对象 —— 留恢复窗口，到期由 FilePurgeTask 判断引用计数后清理。
        //    处理中的文档也允许删，由 AI 服务判断「文档已删除就跳过」。
        int retentionDays = storageProperties.getRetentionDays();
        log.info("删除文档 documentId={} fileName={}（OSS 对象保留 {} 天）",
                documentId, doc.getFileName(), retentionDays);

        auditService.recordSuccess(AuditConstants.ACTION_DOCUMENT_DELETE,
                AuditConstants.RESOURCE_DOCUMENT, String.valueOf(documentId),
                Map.of("fileName", doc.getFileName(),
                       "knowledgeBaseId", doc.getKnowledgeBaseId(),
                       "ossRetentionDays", retentionDays));
    }

    @Override
    public String downloadUrl(Long documentId) {
        Document doc = getOwned(documentId);
        // 借 sys_file 的 objectKey 生成预签名 URL（document.file_url 存的也是它）
        SysFile sysFile = fileService.getById(doc.getFileId());
        if (sysFile == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "文件记录不存在");
        }
        // 返回ossclient生成的PresignedUrl
        return fileService.downloadUrl(sysFile);
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 拿到后缀名
     */
    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase()
                : "";
    }

    // 将Document对象转为DocumentVO对象
    private DocumentVO toVO(Document d) {
        DocumentVO vo = new DocumentVO();
        vo.setDocumentId(d.getId() == null ? null : String.valueOf(d.getId()));
        vo.setKnowledgeBaseId(d.getKnowledgeBaseId() == null
                ? null : String.valueOf(d.getKnowledgeBaseId()));
        vo.setFileName(d.getFileName());
        vo.setFileType(d.getFileType());
        vo.setFileSize(d.getFileSize());
        vo.setStatus(aggregateStatus(d));
        vo.setParseStatus(d.getParseStatus());
        vo.setChunkStatus(d.getChunkStatus());
        vo.setVectorStatus(d.getVectorStatus());
        vo.setErrorMessage(d.getErrorMessage());
        vo.setPageCount(d.getPageCount());
        vo.setCreatedAt(d.getCreatedAt());
        return vo;
    }

    /**
     * 把库里三个独立状态字段聚合成一个对外状态
     * <p>
     * 注意：目前只到 UPLOADED 就停了（PENDING），后面的 PARSING / CHUNKING / EMBEDDING
     * 要等 Python AI 服务接上才会出现。
     */
    private String aggregateStatus(Document d) {
        if (FAILED.equals(d.getParseStatus())
                || FAILED.equals(d.getChunkStatus())
                || FAILED.equals(d.getVectorStatus())) {
            return FAILED;
        }
        if (SUCCESS.equals(d.getVectorStatus())) {
            return "COMPLETED";
        }
        if (SUCCESS.equals(d.getChunkStatus())) {
            return "CHUNKED";
        }
        if (SUCCESS.equals(d.getParseStatus())) {
            return "PARSED";
        }
        if (PARSING.equals(d.getParseStatus())) {
            return "PARSING";
        }
        return "UPLOADED";
    }
}
