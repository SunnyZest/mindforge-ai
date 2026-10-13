package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.InternalApiProperties;
import com.zest.mindforgeai.mapper.DocumentChunkMapper;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.pojo.dto.ChunkBatchDTO;
import com.zest.mindforgeai.pojo.dto.DocumentStatusUpdateDTO;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.entity.DocumentChunk;
import com.zest.mindforgeai.pojo.vo.DocumentClaimVO;
import com.zest.mindforgeai.pojo.vo.DocumentVectorPurgeVO;
import com.zest.mindforgeai.service.FileStorageService;
import com.zest.mindforgeai.service.InternalDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部文档服务实现
 *
 * @author crx
 * @since 2026-10-06
 */
@Slf4j
@Service
public class InternalDocumentServiceImpl implements InternalDocumentService {

    /** 下载地址有效期。Python 认领后要尽快下载，1 小时够用。 */
    private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

    private static final String PENDING = "PENDING";
    private static final String PARSING = "PARSING";

    /** 单次最多返回多少个待清理文档 —— 防止一次拉太多把 AI 服务压垮 */
    private static final int MAX_PURGE_LIMIT = 500;

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final FileStorageService storageService;
    private final InternalApiProperties properties;

    public InternalDocumentServiceImpl(DocumentMapper documentMapper,
                                       DocumentChunkMapper chunkMapper,
                                       FileStorageService storageService,
                                       InternalApiProperties properties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.storageService = storageService;
        this.properties = properties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DocumentClaimVO> claimPending(int limit) {
        // 上限兜底：防止 Python 一次要 10000 条把两边都压垮
        int safeLimit = Math.min(Math.max(1, limit), properties.getClaimLimit());

        // 必须在事务里 —— 出了事务行锁立刻释放，SKIP LOCKED 就白设了
        // 拿到未处理文档
        List<Document> docs = documentMapper.selectPendingForClaim(safeLimit);
        if (docs.isEmpty()) {
            return List.of();
        }

        // 认领 = 改成 PARSING。这样其他实例的 selectPendingForClaim 就不会再拿到它们。
        List<Long> ids = docs.stream().map(Document::getId).toList();
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .in(Document::getId, ids)
                .set(Document::getParseStatus, PARSING)
                .set(Document::getUpdatedAt, LocalDateTime.now()));

        log.info("认领 {} 篇文档：{}", docs.size(), ids);

        return docs.stream().map(this::toClaimVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long documentId, DocumentStatusUpdateDTO dto) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        // 只 set 有值的字段 —— null 表示「这个阶段还没到，别动它」
        LambdaUpdateWrapper<Document> wrapper = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .set(StringUtils.hasText(dto.getParseStatus()),
                        Document::getParseStatus, dto.getParseStatus())
                .set(StringUtils.hasText(dto.getChunkStatus()),
                        Document::getChunkStatus, dto.getChunkStatus())
                .set(StringUtils.hasText(dto.getVectorStatus()),
                        Document::getVectorStatus, dto.getVectorStatus())
                .set(dto.getPageCount() != null, Document::getPageCount, dto.getPageCount())
                .set(Document::getUpdatedAt, LocalDateTime.now());

        // errorMessage 特殊：只要不是 null 就写（空字符串用来「清空上次的错误」）
        if (dto.getErrorMessage() != null) {
            wrapper.set(Document::getErrorMessage, dto.getErrorMessage());
        }

        documentMapper.update(null, wrapper);

        log.info("回写文档状态 documentId={} parse={} chunk={} vector={} error={}",
                documentId, dto.getParseStatus(), dto.getChunkStatus(),
                dto.getVectorStatus(), dto.getErrorMessage());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveChunks(Long documentId, ChunkBatchDTO dto) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        // 先删后插 —— 重复处理同一份文档时不会撞 uk_doc_chunk_index
        chunkMapper.deleteByDocumentId(documentId);

        if (dto.getChunks() == null || dto.getChunks().isEmpty()) {
            return 0;
        }

        List<DocumentChunk> chunks = dto.getChunks().stream()
                .map(item -> new DocumentChunk()
                        .setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId())
                        .setUserId(doc.getUserId())
                        .setKnowledgeBaseId(doc.getKnowledgeBaseId())
                        .setDocumentId(documentId)
                        .setChunkIndex(item.getChunkIndex())
                        .setContent(item.getContent())
                        .setTokenCount(item.getTokenCount())
                        .setPageNo(item.getPageNo())
                        .setVectorId(item.getVectorId())
                        .setMetadataJson(item.getMetadataJson()))
                .toList();

        int inserted = chunkMapper.batchInsert(chunks);
        log.info("写入文档分块 documentId={} 共 {} 条", documentId, inserted);
        return inserted;
    }

    @Override
    public List<DocumentVectorPurgeVO> listPendingVectorPurge(int limit) {
        // 上限兜底：一次清太多会让 AI 服务那边的向量库操作占满内存
        int safeLimit = Math.min(Math.max(1, limit), MAX_PURGE_LIMIT);

        List<Document> docs = documentMapper.selectPendingVectorPurge(safeLimit);
        if (docs.isEmpty()) {
            return List.of();
        }

        log.info("待清理向量的文档 {} 篇：{}",
                docs.size(), docs.stream().map(Document::getId).toList());

        return docs.stream().map(d -> {
            DocumentVectorPurgeVO vo = new DocumentVectorPurgeVO();
            vo.setDocumentId(String.valueOf(d.getId()));
            vo.setKnowledgeBaseId(d.getKnowledgeBaseId() == null
                    ? null : String.valueOf(d.getKnowledgeBaseId()));
            vo.setUserId(d.getUserId() == null ? null : String.valueOf(d.getUserId()));
            vo.setFileName(d.getFileName());
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markVectorPurged(Long documentId) {
        int updated = documentMapper.markVectorPurged(documentId);
        if (updated == 0) {
            // 返回 0 只有两种可能：已经标记过了，或文档没被删除。
            // 两种都不该报错 —— 这个接口是幂等的，重复调用是正常情况。
            log.debug("标记向量已清除：documentId={} 影响 0 行（已标记过或未被删除）", documentId);
            return;
        }
        log.info("标记向量已清除：documentId={}", documentId);
    }

    @Override
    public int reclaimTimedOut() {
        LocalDateTime deadline = LocalDateTime.now()
                .minusMinutes(properties.getClaimTimeoutMinutes());

        int reclaimed = documentMapper.reclaimTimedOut(deadline);
        if (reclaimed > 0) {
            log.warn("回收超时未回写的文档 {} 篇（认领超过 {} 分钟没动静），已改回 PENDING",
                    reclaimed, properties.getClaimTimeoutMinutes());
        }
        return reclaimed;
    }

    /* ---------- 内部工具 ---------- */

    private DocumentClaimVO toClaimVO(Document d) {
        DocumentClaimVO vo = new DocumentClaimVO();
        vo.setDocumentId(String.valueOf(d.getId()));
        vo.setKnowledgeBaseId(d.getKnowledgeBaseId() == null
                ? null : String.valueOf(d.getKnowledgeBaseId()));
        vo.setUserId(d.getUserId() == null ? null : String.valueOf(d.getUserId()));
        vo.setFileName(d.getFileName());
        vo.setFileType(d.getFileType());
        vo.setFileSize(d.getFileSize());
        // file_url 里存的是 objectKey，这里换成有时效的预签名地址给 Python 下载
        vo.setDownloadUrl(storageService.presignedUrl(d.getFileUrl(), DOWNLOAD_TTL));
        return vo;
    }
}
