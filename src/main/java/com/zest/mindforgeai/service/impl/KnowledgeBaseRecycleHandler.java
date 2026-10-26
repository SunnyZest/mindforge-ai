package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.utils.DocumentStatusUtils;
import com.zest.mindforgeai.mapper.ConversationMapper;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.pojo.dto.KnowledgeBaseRecyclePayload;
import com.zest.mindforgeai.pojo.entity.Conversation;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.service.RecycleRestoreHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库的回收站恢复策略。
 *
 * <p>知识库删除几乎全是逻辑删（见 {@code KnowledgeBaseCascadeServiceImpl}）：
 * {@code knowledge_base} / {@code document} / {@code conversation} 置 deleted，
 * {@code sys_file} 软删，{@code document_chunk} 压根不动。
 *
 * <p>所以恢复 = 把这几张表的 {@code deleted} 精确改回 0（只改删除那一刻被级联删掉的），
 * 再把 {@code sys_file} 的 {@code deleted_at} 清空。
 *
 * <p>★ <b>唯一的坑还是向量</b>：文档被逻辑删后，Python 会按
 * {@code deleted = 1 AND vector_purged = 0} 清掉 Chroma 里的向量。
 * 恢复时如果有文档的 {@code vector_purged = 1}，说明向量已经没了 ——
 * 这些文档在库里、正文也在，但**搜不到**。此时把它们标成 PENDING 触发重新处理，
 * 并返回 false 让前端提示用户。
 *
 * @author crx
 * @since 2026-10-26
 */
@Slf4j
@Component
public class KnowledgeBaseRecycleHandler implements RecycleRestoreHandler {

    private final DocumentMapper documentMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public KnowledgeBaseRecycleHandler(DocumentMapper documentMapper,
                                       ConversationMapper conversationMapper,
                                       ObjectMapper objectMapper,
                                       JdbcTemplate jdbc) {
        this.documentMapper = documentMapper;
        this.conversationMapper = conversationMapper;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public RecycleResourceType type() {
        return RecycleResourceType.KNOWLEDGE_BASE;
    }

    @Override
    public String capturePayload(Long knowledgeBaseId) {
        // 此刻还没删，查到的就是「即将被级联删掉」的那些
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .select(Document::getId, Document::getFileId)
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId));
        List<Long> documentIds = documents.stream().map(Document::getId).toList();
        List<Long> fileIds = documents.stream()
                .map(Document::getFileId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> conversationIds = conversationMapper.selectList(
                        new LambdaQueryWrapper<Conversation>()
                                .select(Conversation::getId)
                                .eq(Conversation::getKnowledgeBaseId, knowledgeBaseId))
                .stream().map(Conversation::getId).toList();

        try {
            return objectMapper.writeValueAsString(
                    new KnowledgeBaseRecyclePayload(documentIds, conversationIds, fileIds));
        } catch (JsonProcessingException e) {
            log.error("采集知识库回收站快照失败 kbId={}，恢复后文档与对话会缺失", knowledgeBaseId, e);
            return null;
        }
    }

    @Override
    public boolean restoreRelated(Long knowledgeBaseId, String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            log.warn("知识库回收站记录没有 payload，只能恢复主表 kbId={}（文档与对话会缺失）", knowledgeBaseId);
            return false;
        }

        KnowledgeBaseRecyclePayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, KnowledgeBaseRecyclePayload.class);
        } catch (JsonProcessingException e) {
            log.error("解析知识库回收站快照失败 kbId={}，只恢复主表", knowledgeBaseId, e);
            return false;
        }

        int docs = undelete("document", payload.getDocumentIds());
        int convs = undelete("conversation", payload.getConversationIds());
        int files = restoreFiles(payload.getFileIds());

        log.info("知识库关联数据已恢复 kbId={} 文档={} 对话={} 文件={}",
                knowledgeBaseId, docs, convs, files);

        // ★ 检查向量：文档被逻辑删后 Python 可能已经把 Chroma 向量清了。
        //   这些文档能打开、正文也在，但检索搜不到 —— 必须让用户知道。
        List<Long> documentIds = payload.getDocumentIds();
        if (documentIds == null || documentIds.isEmpty()) {
            return true;
        }
        String in = placeholders(documentIds.size());
        Integer purged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM document WHERE deleted = 0 AND vector_purged = 1 AND id IN (" + in + ")",
                Integer.class, documentIds.toArray());
        if (purged == null || purged == 0) {
            return true;
        }

        // 标 PENDING 触发重新处理（Python 会重新解析 + 分块 + 向量化）
        jdbc.update("UPDATE document SET vector_status = ?, updated_at = NOW() "
                        + "WHERE deleted = 0 AND vector_purged = 1 AND id IN (" + in + ")",
                concatArgs(DocumentStatusUtils.PENDING, documentIds));
        log.warn("知识库恢复后有 {} 篇文档的向量已被清理，已标记 PENDING 等待重新处理 kbId={}",
                purged, knowledgeBaseId);
        return false;
    }

    /** 精确还原：只改「删除那一刻被级联删掉」的那些，不按 kb_id 全量捞 */
    private int undelete(String table, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // ⚠️ 原生 SQL：MP 会给 update 自动补 deleted = 0，永远改不到 deleted = 1 的行
        return jdbc.update("UPDATE " + table + " SET deleted = 0 WHERE deleted = 1 AND id IN ("
                + placeholders(ids.size()) + ")", ids.toArray());
    }

    /** sys_file 必须清 deleted_at，否则 FilePurgeTask 会按保留期把 OSS 对象真删掉 */
    private int restoreFiles(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("UPDATE sys_file SET deleted = 0, deleted_at = NULL "
                + "WHERE deleted = 1 AND id IN (" + placeholders(fileIds.size()) + ")", fileIds.toArray());
    }

    private String placeholders(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> "?")
                .collect(Collectors.joining(","));
    }

    /** 把「状态值 + id 列表」拼成 JdbcTemplate 需要的有序参数数组 */
    private Object[] concatArgs(String status, List<Long> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = status;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return args;
    }
}
