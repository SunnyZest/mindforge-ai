package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ChatConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.ChatMessageMapper;
import com.zest.mindforgeai.mapper.ChatSourceMapper;
import com.zest.mindforgeai.mapper.ConversationMapper;
import com.zest.mindforgeai.mapper.DocumentChunkMapper;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.pojo.dto.RagFeedbackDTO;
import com.zest.mindforgeai.pojo.dto.RagSearchDTO;
import com.zest.mindforgeai.pojo.dto.SendMessageDTO;
import com.zest.mindforgeai.pojo.entity.ChatMessage;
import com.zest.mindforgeai.pojo.entity.ChatSource;
import com.zest.mindforgeai.pojo.entity.Conversation;
import com.zest.mindforgeai.pojo.entity.DocumentChunk;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.vo.SendMessageResultVO;
import com.zest.mindforgeai.pojo.vo.SourceVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.ChatService;
import com.zest.mindforgeai.service.ConversationService;
import com.zest.mindforgeai.service.KnowledgeBaseService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 对话与 RAG 实现。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final ConversationService conversationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AiServiceClient aiClient;
    private final ChatMessageMapper messageMapper;
    private final ChatSourceMapper sourceMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final ConversationMapper conversationMapper;
    private final AuditService auditService;

    public ChatServiceImpl(ConversationService conversationService,
                           KnowledgeBaseService knowledgeBaseService,
                           AiServiceClient aiClient,
                           ChatMessageMapper messageMapper,
                           ChatSourceMapper sourceMapper,
                           DocumentMapper documentMapper,
                           DocumentChunkMapper chunkMapper,
                           ConversationMapper conversationMapper,
                           AuditService auditService) {
        this.conversationService = conversationService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.aiClient = aiClient;
        this.messageMapper = messageMapper;
        this.sourceMapper = sourceMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.conversationMapper = conversationMapper;
        this.auditService = auditService;
    }

    /**
     * 发消息。
     * <p>
     * <b>刻意不把整段逻辑包在一个事务里</b> —— 调用 AI 服务最长要等 120 秒，
     * 一个 120 秒的数据库事务会把连接池占死，几个人同时提问就能拖垮整个服务。
     * <p>
     * 所以拆成三段：
     * <ol>
     *   <li>存用户消息（短事务）</li>
     *   <li>调 AI 服务（<b>无事务</b>）</li>
     *   <li>存助手消息 + 引用（短事务）</li>
     * </ol>
     * 代价是「AI 调用失败时，用户消息已经落库了」—— 但这符合直觉：
     * 用户确实发过这条消息，前端刷新后能看到，只是没有回答。
     */
    @Override
    public SendMessageResultVO sendMessage(Long conversationId, SendMessageDTO dto) {
        Conversation conversation = conversationService.requireOwned(conversationId);
        Long userId = SecurityUtils.getUserId();
        Long kbId = conversation.getKnowledgeBaseId();
        if (kbId == null) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "该会话没有关联知识库，无法提问");
        }

        // ① 存用户消息
        saveMessage(conversationId, userId, ChatConstants.ROLE_USER, dto.getMessage(), null);

        // ② 取最近几轮历史（在存完用户消息之后取，这样上下文是完整的）
        List<AiServiceClient.HistoryItem> history = loadHistory(conversationId);

        // ③ 调 AI 服务 —— 这一步不占数据库事务
        AiServiceClient.ChatData data = aiClient.chat(new AiServiceClient.ChatRequest(
                String.valueOf(userId),
                String.valueOf(kbId),
                String.valueOf(conversationId),
                dto.getMessage(),
                dto.getTopK(),
                Boolean.TRUE.equals(dto.getEnableRerank()),
                history));

        // ④ 存助手消息 + 引用
        String modelName = conversation.getModelName();
        Long assistantId = saveMessage(conversationId, userId, ChatConstants.ROLE_ASSISTANT,
                data.answer(), modelName);
        List<SourceVO> sources = saveSources(assistantId, data.sources());

        // ⑤ 会话的 updated_at 要更新，否则列表排序不对（按最后活跃时间排）
        touchConversation(conversationId);

        log.info("对话完成 conversationId={} 用户消息 {} 字，回答 {} 字，引用 {} 条",
                conversationId, dto.getMessage().length(),
                data.answer() == null ? 0 : data.answer().length(), sources.size());

        SendMessageResultVO result = new SendMessageResultVO();
        result.setMessageId(String.valueOf(assistantId));
        result.setAnswer(data.answer());
        result.setSources(sources);
        result.setHasContext(data.hasContext());
        result.setHasAnswer(data.hasAnswer());
        return result;
    }

    @Override
    public List<SourceVO> search(RagSearchDTO dto) {
        // 校验知识库归属 —— 不校验的话传别人的 kbId 就能检索别人的资料
        Long kbId = Long.valueOf(dto.getKnowledgeBaseId());
        knowledgeBaseService.getOwned(kbId);

        AiServiceClient.SearchData data = aiClient.search(new AiServiceClient.SearchRequest(
                String.valueOf(SecurityUtils.getUserId()),
                String.valueOf(kbId),
                dto.getQuery(),
                dto.getTopK(),
                Boolean.TRUE.equals(dto.getEnableRerank())));

        return toSourceVOs(data.results());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feedback(RagFeedbackDTO dto) {
        Long messageId = Long.valueOf(dto.getMessageId());
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BizException(ErrorCode.MESSAGE_NOT_FOUND);
        }

        // 只能给自己的消息打分；不是自己的报「不存在」，不报 403
        if (!Objects.equals(message.getUserId(), SecurityUtils.getUserId())) {
            log.warn("用户 {} 试图给不属于自己的消息 {} 打分",
                    SecurityUtils.getUserId(), messageId);
            throw new BizException(ErrorCode.MESSAGE_NOT_FOUND);
        }

        // 只有助手消息能打分 —— 给自己的提问打分没有意义
        if (!ChatConstants.ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "只能对回答进行评价");
        }

        // 重复提交覆盖上一次 —— 一条消息只保留一个反馈
        messageMapper.update(null, Wrappers.<ChatMessage>lambdaUpdate()
                .eq(ChatMessage::getId, messageId)
                .set(ChatMessage::getRating, dto.getRating())
                .set(ChatMessage::getFeedbackReason, dto.getReason())
                .set(ChatMessage::getFeedbackAt, LocalDateTime.now()));

        auditService.recordSuccess(AuditConstants.ACTION_RAG_FEEDBACK,
                AuditConstants.RESOURCE_CONVERSATION, String.valueOf(message.getConversationId()),
                Map.of("messageId", dto.getMessageId(),
                       "rating", dto.getRating()));
    }

    // ---------- 内部 ----------

    private Long saveMessage(Long conversationId, Long userId, String role,
                             String content, String modelName) {
        ChatMessage message = new ChatMessage()
                .setConversationId(conversationId)
                .setUserId(userId)
                .setRole(role)
                .setContent(content == null ? "" : content)
                .setModelName(modelName)
                .setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message.getId();
    }

    /**
     * 取最近几轮历史，转成 AI 服务要的格式（时间正序）。
     * <p>
     * 先倒序取 N 条再翻回来 —— 直接正序取会拿到「最早的几条」，
     * 那对多轮对话没用。
     */
    private List<AiServiceClient.HistoryItem> loadHistory(Long conversationId) {
        int limit = ChatConstants.MAX_HISTORY_ROUNDS * 2;

        List<ChatMessage> recent = messageMapper.selectList(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .in(ChatMessage::getRole, List.of(ChatConstants.ROLE_USER,
                                ChatConstants.ROLE_ASSISTANT))
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT " + limit));

        if (recent.isEmpty()) {
            return List.of();
        }
        Collections.reverse(recent);

        // AI 服务那边只认 user / assistant（小写）
        return recent.stream()
                .map(m -> new AiServiceClient.HistoryItem(
                        ChatConstants.ROLE_USER.equals(m.getRole()) ? "user" : "assistant",
                        m.getContent()))
                .toList();
    }

    /**
     * 存引用来源，同时返回给前端的 VO。
     * <p>
     * 一次查出涉及的文档名，避免逐条查 document。
     */
    private List<SourceVO> saveSources(Long messageId, List<AiServiceClient.Source> sources) {
        List<SourceVO> result = toSourceVOs(sources);

        for (SourceVO vo : result) {
            sourceMapper.insert(new ChatSource()
                    .setMessageId(messageId)
                    .setDocumentId(Long.valueOf(vo.getDocumentId()))
                    .setChunkId(Long.valueOf(vo.getChunkId()))
                    .setPageNo(vo.getPageNo())
                    .setRelevanceScore(vo.getScore() == null
                            ? null : BigDecimal.valueOf(vo.getScore()))
                    .setSourceTitle(vo.getDocumentName())
                    .setCreatedAt(LocalDateTime.now()));
        }
        return result;
    }

    /**
     * 把 AI 服务返回的引用转成 VO（顺带补上文档名）。
     * <p>
     * <b>⚠️ 这里有个字段语义的坑，别想当然：</b>
     * AI 服务返回的 {@code chunkId} <b>不是</b> {@code document_chunk.id}，
     * 而是向量库里的 {@code vector_id}，形如 {@code doc46-chunk0}。
     * <p>
     * 原因是时序：Python 向量化的时候，{@code document_chunk.id} 还没生成
     * （那是 Java 批量插入之后才有的），所以它只能用
     * {@code doc{documentId}-chunk{chunkIndex}} 当外部标识。
     * <p>
     * 但 {@code chat_source.chunk_id} 要指向 {@code document_chunk} 表，
     * 而且前端要拿它调 {@code GET /api/app/chunks/{id}} 取原文 ——
     * 存 vector_id 的话那个接口查不到东西。
     * 所以这里用 {@code (documentId, chunkIndex)} 反查真正的分块 id。
     */
    private List<SourceVO> toSourceVOs(List<AiServiceClient.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }

        List<Long> documentIds = sources.stream()
                .map(s -> parseLong(s.documentId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 一次查出涉及的文档名 + 这些文档的全部分块，避免逐条查
        Map<Long, String> nameMap = documentIds.isEmpty() ? Map.of()
                : documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                        .select(Document::getId, Document::getFileName)
                        .in(Document::getId, documentIds))
                .stream()
                .collect(Collectors.toMap(Document::getId, Document::getFileName,
                        (a, b) -> a));

        Map<String, Long> chunkIdMap = documentIds.isEmpty() ? Map.of()
                : chunkMapper.selectList(Wrappers.<DocumentChunk>lambdaQuery()
                        .select(DocumentChunk::getId, DocumentChunk::getDocumentId,
                                DocumentChunk::getChunkIndex)
                        .in(DocumentChunk::getDocumentId, documentIds))
                .stream()
                .collect(Collectors.toMap(
                        c -> chunkKey(c.getDocumentId(), c.getChunkIndex()),
                        DocumentChunk::getId,
                        (a, b) -> a));

        List<SourceVO> result = new ArrayList<>(sources.size());

        for (AiServiceClient.Source src : sources) {
            Long documentId = parseLong(src.documentId());
            if (documentId == null) {
                log.warn("AI 服务返回的引用缺少 documentId，已跳过：{}", src);
                continue;
            }

            Long chunkId = chunkIdMap.get(chunkKey(documentId, src.chunkIndex()));
            if (chunkId == null) {
                // 分块可能刚被重处理过（先删后插），向量库还是旧的 —— 这种悬空引用直接跳过，
                // 总比让前端点进去报 404 好
                log.warn("找不到对应的分块（documentId={} chunkIndex={} vectorId={}），已跳过",
                        documentId, src.chunkIndex(), src.chunkId());
                continue;
            }

            ChatSource source = new ChatSource()
                    .setDocumentId(documentId)
                    .setChunkId(chunkId)
                    .setPageNo(src.chunkIndex())
                    .setRelevanceScore(src.score() == null
                            ? null : BigDecimal.valueOf(src.score()));

            result.add(SourceVO.of(source, nameMap.get(documentId)));
        }

        return result;
    }

    /** 分块在「文档 + 序号」维度上的唯一键，用来把向量库的引用映射回数据库 id */
    private String chunkKey(Long documentId, Integer chunkIndex) {
        return documentId + ":" + (chunkIndex == null ? -1 : chunkIndex);
    }

    /** 会话列表按 updated_at 排序，所以每次对话后要把它顶上去 */
    private void touchConversation(Long conversationId) {
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getUpdatedAt, LocalDateTime.now()));
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("AI 服务返回的 id 不是数字：{}", value);
            return null;
        }
    }

}
