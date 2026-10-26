package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zest.mindforgeai.mapper.ConversationMapper;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.mapper.KnowledgeBaseMapper;
import com.zest.mindforgeai.pojo.entity.Conversation;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.service.KnowledgeBaseCascadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    private final FileService fileService;

    public KnowledgeBaseCascadeServiceImpl(DocumentMapper documentMapper,
                                           ConversationMapper conversationMapper,
                                           KnowledgeBaseMapper knowledgeBaseMapper,
                                           FileService fileService) {
        this.documentMapper = documentMapper;
        this.conversationMapper = conversationMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.fileService = fileService;
    }

    @Override
    public CascadeResult deleteCascade(Long knowledgeBaseId) {
        // 先查出该知识库下未删除文档关联的 fileId，同步做 sys_file 软删除（供 FilePurgeTask 清理底层存储）
        // ⚠️ 两个坑叠在一起，都踩过：
        //   1. 写成 stream().map(getFileId).filter(nonNull).toList() 时，文档没有 file_id
        //      会抛 NPE，堆栈指向 toList，看不出跟 file_id 有关；
        //   2. 只 select 一列时，若该列全是 null，MyBatis 会返回 **null 元素**
        //      （returnInstanceForEmptyRow 默认 false），所以必须判 doc != null。
        Set<Long> fileIdSet = new LinkedHashSet<>();
        for (Document doc : documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .select(Document::getFileId)
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId))) {
            if (doc != null && doc.getFileId() != null) {
                fileIdSet.add(doc.getFileId());
            }
        }
        List<Long> fileIds = new ArrayList<>(fileIdSet);

        // ⚠️ 没有外键级联，关联数据必须手动清理。
        //    document / conversation 都有 deleted 列 → MP 的 delete() 走逻辑删除。
        //    document_chunk / chat_message / chat_source 没有 deleted 列，
        //    物理删除代价大（chunk 数据量可能很大），这里不动它们，留待后台清理任务。
        //    安全性：RAG 检索会 JOIN document 并过滤 deleted = 0，不会召回已删文档的内容。
        int docs = documentMapper.delete(new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId));
        // ★ 一次查出所有文件，循环里只做内存判断。
        //   逐个 getById 就是 N+1：一个知识库下有 N 篇文档 = N 次查询。
        //   ⚠️ 空列表必须跳过：MP 的 selectBatchIds([]) 会拼出 IN ()，
        //      MySQL 直接语法错误（新建的知识库还没有文档时就会踩到）。
        if (!fileIds.isEmpty()) {
            for (SysFile sysFile : fileService.listByIds(fileIds)) {
                fileService.softDelete(sysFile);
            }
        }
        int convs = conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getKnowledgeBaseId, knowledgeBaseId));

        // knowledge_base 有 deleted 列 → 逻辑删除
        knowledgeBaseMapper.deleteById(knowledgeBaseId);

        log.info("级联删除知识库 id={}：文档 {} 篇、对话 {} 个", knowledgeBaseId, docs, convs);
        return new CascadeResult(docs, convs);
    }
}
