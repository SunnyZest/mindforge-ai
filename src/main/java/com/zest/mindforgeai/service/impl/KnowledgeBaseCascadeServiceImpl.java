package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zest.mindforgeai.mapper.ConversationMapper;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.mapper.KnowledgeBaseMapper;
import com.zest.mindforgeai.pojo.entity.Conversation;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.service.KnowledgeBaseCascadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 知识库级联清理实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class KnowledgeBaseCascadeServiceImpl implements KnowledgeBaseCascadeService {

    private final DocumentMapper documentMapper;
    private final ConversationMapper conversationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseCascadeServiceImpl(DocumentMapper documentMapper,
                                           ConversationMapper conversationMapper,
                                           KnowledgeBaseMapper knowledgeBaseMapper) {
        this.documentMapper = documentMapper;
        this.conversationMapper = conversationMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public CascadeResult deleteCascade(Long knowledgeBaseId) {
        // ⚠️ 没有外键级联，关联数据必须手动清理。
        //    document / conversation 都有 deleted 列 → MP 的 delete() 走逻辑删除。
        //    document_chunk / chat_message / chat_source 没有 deleted 列，
        //    物理删除代价大（chunk 数据量可能很大），这里不动它们，留待后台清理任务。
        //    安全性：RAG 检索会 JOIN document 并过滤 deleted = 0，不会召回已删文档的内容。
        int docs = documentMapper.delete(new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId));
        int convs = conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getKnowledgeBaseId, knowledgeBaseId));

        // knowledge_base 有 deleted 列 → 逻辑删除
        knowledgeBaseMapper.deleteById(knowledgeBaseId);

        log.info("级联删除知识库 id={}：文档 {} 篇、对话 {} 个", knowledgeBaseId, docs, convs);
        return new CascadeResult(docs, convs);
    }
}
