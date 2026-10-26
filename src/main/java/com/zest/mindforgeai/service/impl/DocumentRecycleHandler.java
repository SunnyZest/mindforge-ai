package com.zest.mindforgeai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.pojo.dto.DocumentRecyclePayload;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.service.RecycleRestoreHandler;
import com.zest.mindforgeai.utils.DocumentStatusUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档的回收站恢复策略。
 *
 * <p>删文档只做两件事（{@code DocumentServiceImpl.delete}）：
 * {@code document} 逻辑删、{@code sys_file} 软删，**{@code document_chunk} 完全不动**。
 *
 * <p>所以恢复比职位/面试/对话都简单：主表改回 0（RecycleBinServiceImpl 统一做）+
 * 把 {@code sys_file} 的 {@code deleted_at} 清空。chunk 文本和 {@code vector_id} 本来就在。
 *
 * <p>★ <b>唯一的坑是向量</b>：Python 按 {@code deleted = 1 AND vector_purged = 0}
 * 清 Chroma 里的向量。清掉之后文档能打开、正文也在，但检索搜不到 ——
 * 这时标 {@code vector_status = PENDING} 触发重新处理，并返回 false 让前端提示。
 *
 * @author crx
 * @since 2026-10-26
 */
@Slf4j
@Component
public class DocumentRecycleHandler implements RecycleRestoreHandler {

    private final DocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public DocumentRecycleHandler(DocumentMapper documentMapper,
                                  ObjectMapper objectMapper,
                                  JdbcTemplate jdbc) {
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public RecycleResourceType type() {
        return RecycleResourceType.DOCUMENT;
    }

    @Override
    public String capturePayload(Long documentId) {
        // 此刻 document 还没被删（deleted = 0），能查到 fileId
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new DocumentRecyclePayload(doc.getFileId()));
        } catch (JsonProcessingException e) {
            log.error("采集文档回收站快照失败 documentId={}（恢复时会回退查主表，不影响结果）", documentId, e);
            return null;
        }
    }

    @Override
    public boolean restoreRelated(Long documentId, String payloadJson) {
        // ① fileId：payload 优先，没有就回退查主表 —— 主表还在，所以快照丢了也能恢复
        Long fileId = parseFileId(payloadJson);
        Document doc = documentMapper.selectById(documentId);
        if (fileId == null && doc != null) {
            fileId = doc.getFileId();
        }

        // ② 恢复 sys_file：必须清 deleted_at，否则 FilePurgeTask 会按保留期把 OSS 对象真删掉。
        //    ⚠️ 走原生 SQL —— MP 会给 update 自动补 deleted = 0，改不到 deleted = 1 的行。
        if (fileId != null) {
            int restored = jdbc.update(
                    "UPDATE sys_file SET deleted = 0, deleted_at = NULL WHERE id = ? AND deleted = 1", fileId);
            if (restored == 0) {
                // 恢复窗口和 OSS 保留期都是 30 天，但会错位：第 29 天恢复、第 30 天文件被清。
                // 这时文档记录和 chunk 都能回来，只是下载不了 —— 记日志，不算恢复失败。
                log.warn("文档原始文件已不可恢复（可能被 FilePurgeTask 清理）documentId={} fileId={}",
                        documentId, fileId);
            }
        }

        // ③ 检查向量是否已被 Python 清掉
        if (doc != null && Integer.valueOf(1).equals(doc.getVectorPurged())) {
            // 标 PENDING 触发重新处理（Python 会重新解析 + 分块 + 向量化）
            jdbc.update("UPDATE document SET vector_status = ?, updated_at = NOW() WHERE id = ?",
                    DocumentStatusUtils.PENDING, documentId);
            log.warn("文档向量已被清理，已标记 PENDING 等待重新处理 documentId={}", documentId);
            return false;
        }
        return true;
    }

    private Long parseFileId(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            DocumentRecyclePayload payload =
                    objectMapper.readValue(payloadJson, DocumentRecyclePayload.class);
            return payload == null ? null : payload.getFileId();
        } catch (JsonProcessingException e) {
            log.warn("解析文档回收站快照失败，回退查主表 documentId 见调用方", e);
            return null;
        }
    }
}
