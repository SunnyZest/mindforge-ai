package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.UploadConstants;
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
import com.zest.mindforgeai.utils.DocumentStatusUtils;
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

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。 */
    private static final Map<String, SFunction<Document, ?>> SORTABLE = Map.of(
            "fileName", Document::getFileName,
            "fileSize", Document::getFileSize,
            "createdAt", Document::getCreatedAt,
            "updatedAt", Document::getUpdatedAt);

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
        if (Integer.valueOf(1).equals(kb.getIsSystem())) {
            throw new BizException(ErrorCode.FORBIDDEN, "官方智库由系统维护，普通用户无权上传文档");
        }

        // ② 校验文件名与类型
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }
        // 拿到后缀名，文件类型
        String ext = UploadConstants.extensionOf(originalName);
        if (!UploadConstants.isAllowed(originalName, UploadConstants.DOCUMENT_EXT)) {
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
                .setParseStatus(DocumentStatusUtils.PENDING)
                .setChunkStatus(DocumentStatusUtils.PENDING)
                .setVectorStatus(DocumentStatusUtils.PENDING);
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
                                                  String keyword,
                                                  String sortBy, String sortOrder) {
        // 先校验知识库访问权限（本人私有库 或 官方全局智库）
        knowledgeBaseService.getAccessible(knowledgeBaseId);

        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
                .like(StringUtils.hasText(keyword), Document::getFileName, keyword);

        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, Document::getId, false);

        Page<Document> result = page(new Page<>(safePage, safeSize), wrapper);

        return PageResultVO.of(result,
                result.getRecords().stream().map(this::toVO).toList());
    }

    @Override
    public DocumentVO getDetail(Long documentId) {
        return toVO(getAccessible(documentId));
    }

    @Override
    public DocumentStatusVO getStatus(Long documentId) {
        Document d = getAccessible(documentId);

        DocumentStatusVO vo = new DocumentStatusVO();
        vo.setDocumentId(String.valueOf(d.getId()));
        vo.setStatus(DocumentStatusUtils.aggregate(d));
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
        if (!DocumentStatusUtils.FAILED.equals(doc.getParseStatus())
                && !DocumentStatusUtils.FAILED.equals(doc.getChunkStatus())
                && !DocumentStatusUtils.FAILED.equals(doc.getVectorStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "只有处理失败的文档可以重试，当前状态：" + DocumentStatusUtils.aggregate(doc));
        }

        String previousStatus = DocumentStatusUtils.aggregate(doc);

        // 三个状态一起重置，并清掉上次的错误信息。
        // 用 LambdaUpdateWrapper 而不是 updateById：MP 的 updateById 默认忽略 null 字段，
        // 传 null 的 errorMessage 根本不会被写进 SQL，旧错误信息会残留。
        update(new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .set(Document::getParseStatus, DocumentStatusUtils.PENDING)
                .set(Document::getChunkStatus, DocumentStatusUtils.PENDING)
                .set(Document::getVectorStatus, DocumentStatusUtils.PENDING)
                .set(Document::getErrorMessage, null)
                .set(Document::getUpdatedAt, LocalDateTime.now()));

        // 重试 = 重置为 PENDING，由 Python AI 服务的轮询循环下一轮认领处理
        // （claim 时会跳过非 PENDING 的文档，所以不用主动通知）。
        log.info("重试文档 documentId={} fileName={}，状态已重置为 PENDING（等待 AI 服务认领）",
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
            // ⚠️ 刻意抛「不存在」而不是「无权访问」：
            // 资源 id 是连续自增的数字，抛 403 等于告诉调用方
            // 「这个 id 是存在的，只是不归你」——相当于帮他确认了别人的数据范围。
            // 抛 404 什么都不泄漏。
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
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
        Document doc = getAccessible(documentId);
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
     * 校验文档访问权限：本人私有文档 或 所属知识库为官方全局智库。
     */
    private Document getAccessible(Long documentId) {
        Document doc = getById(documentId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (doc.getUserId().equals(SecurityUtils.getUserId())) {
            return doc;
        }
        KnowledgeBase kb = knowledgeBaseService.getById(doc.getKnowledgeBaseId());
        if (kb != null && Integer.valueOf(1).equals(kb.getIsSystem())) {
            return doc;
        }
        throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
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
        vo.setStatus(DocumentStatusUtils.aggregate(d));
        vo.setParseStatus(d.getParseStatus());
        vo.setChunkStatus(d.getChunkStatus());
        vo.setVectorStatus(d.getVectorStatus());
        vo.setErrorMessage(d.getErrorMessage());
        vo.setPageCount(d.getPageCount());
        vo.setCreatedAt(d.getCreatedAt());
        return vo;
    }
}
