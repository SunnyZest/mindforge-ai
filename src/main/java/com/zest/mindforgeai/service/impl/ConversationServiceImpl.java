package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ChatConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.ChatMessageMapper;
import com.zest.mindforgeai.mapper.ChatSourceMapper;
import com.zest.mindforgeai.mapper.ConversationMapper;
import com.zest.mindforgeai.pojo.dto.CreateConversationDTO;
import com.zest.mindforgeai.pojo.entity.ChatMessage;
import com.zest.mindforgeai.pojo.entity.ChatSource;
import com.zest.mindforgeai.pojo.entity.Conversation;
import com.zest.mindforgeai.pojo.vo.ConversationDetailVO;
import com.zest.mindforgeai.pojo.vo.ConversationVO;
import com.zest.mindforgeai.pojo.vo.MessageVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.SourceVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.ConversationService;
import com.zest.mindforgeai.service.KnowledgeBaseService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话管理实现。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    private static final long MAX_PAGE_SIZE = 100;

    /** 会话默认启用状态（conversation.status 是 tinyint，1 = 正常） */
    private static final int STATUS_ACTIVE = 1;

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。 */
    private static final Map<String, SFunction<Conversation, ?>> SORTABLE = Map.of(
            "title", Conversation::getTitle,
            "createdAt", Conversation::getCreatedAt,
            "updatedAt", Conversation::getUpdatedAt);

    private final ChatMessageMapper messageMapper;
    private final ChatSourceMapper sourceMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AuditService auditService;

    public ConversationServiceImpl(ChatMessageMapper messageMapper,
                                   ChatSourceMapper sourceMapper,
                                   KnowledgeBaseService knowledgeBaseService,
                                   AuditService auditService) {
        this.messageMapper = messageMapper;
        this.sourceMapper = sourceMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.auditService = auditService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO create(CreateConversationDTO dto) {
        // 知识库必须存在且属于当前用户 —— 借 KnowledgeBaseService 的归属校验，
        // 不自己写一遍（否则两处逻辑容易走偏）
        Long kbId = Long.valueOf(dto.getKnowledgeBaseId());
        knowledgeBaseService.getOwned(kbId);

        Conversation conversation = new Conversation()
                .setUserId(SecurityUtils.getUserId())
                .setKnowledgeBaseId(kbId)
                .setTitle(dto.getTitle().trim())
                .setStatus(STATUS_ACTIVE)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        save(conversation);

        auditService.recordSuccess(AuditConstants.ACTION_CONVERSATION_CREATE,
                AuditConstants.RESOURCE_CONVERSATION, String.valueOf(conversation.getId()),
                Map.of("title", conversation.getTitle(), "knowledgeBaseId", kbId));

        return ConversationVO.of(conversation, 0L);
    }

    @Override
    public PageResultVO<ConversationVO> page(long page, long pageSize,
                                             String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, SecurityUtils.getUserId());
        // 兜底按 updatedAt 倒序 —— 最近聊过的排最前
        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE,
                Conversation::getUpdatedAt, false);

        IPage<Conversation> result = page(new Page<>(safePage, safeSize), wrapper);

        List<Conversation> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResultVO.of(result, List.of());
        }

        // 一次性把这一页所有会话的消息数查出来，避免逐行 count 造成 N+1
        List<Long> ids = records.stream().map(Conversation::getId).toList();
        Map<Long, Long> countMap = messageMapper.selectList(
                        Wrappers.<ChatMessage>lambdaQuery().in(ChatMessage::getConversationId, ids))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ChatMessage::getConversationId, java.util.stream.Collectors.counting()));

        return PageResultVO.of(result, records.stream()
                .map(c -> ConversationVO.of(c, countMap.getOrDefault(c.getId(), 0L)))
                .toList());
    }

    @Override
    public ConversationDetailVO detail(Long conversationId) {
        Conversation conversation = requireOwned(conversationId);

        List<ChatMessage> messages = messageMapper.selectList(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId));

        ConversationDetailVO vo = new ConversationDetailVO();
        ConversationVO base = ConversationVO.of(conversation, (long) messages.size());
        vo.setConversationId(base.getConversationId());
        vo.setKnowledgeBaseId(base.getKnowledgeBaseId());
        vo.setTitle(base.getTitle());
        vo.setModelName(base.getModelName());
        vo.setMessageCount(base.getMessageCount());
        vo.setCreatedAt(base.getCreatedAt());
        vo.setUpdatedAt(base.getUpdatedAt());
        vo.setMessages(messages.stream().map(MessageVO::of).toList());

        // 助手消息带上引用来源 —— 前端要能点开看原文
        fillSources(vo.getMessages());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long conversationId) {
        Conversation conversation = requireOwned(conversationId);

        // ⚠️ 没有外键级联，必须手动清三张表。
        //    conversation 有 deleted 列 → 逻辑删除；
        //    chat_message / chat_source 没有 → 物理删除（跟会话一起消失，不留历史）。
        //    顺序：先删引用（依赖消息），再删消息，最后删会话。
        List<Long> messageIds = messageMapper.selectList(
                        Wrappers.<ChatMessage>lambdaQuery()
                                .select(ChatMessage::getId)
                                .eq(ChatMessage::getConversationId, conversationId))
                .stream().map(ChatMessage::getId).toList();

        int sources = 0;
        if (!messageIds.isEmpty()) {
            sources = sourceMapper.delete(Wrappers.<ChatSource>lambdaQuery()
                    .in(ChatSource::getMessageId, messageIds));
        }
        int messages = messageMapper.delete(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId));
        removeById(conversationId);

        log.info("删除会话 id={} title={}，级联清理消息 {} 条、引用 {} 条",
                conversationId, conversation.getTitle(), messages, sources);

        auditService.recordSuccess(AuditConstants.ACTION_CONVERSATION_DELETE,
                AuditConstants.RESOURCE_CONVERSATION, String.valueOf(conversationId),
                Map.of("title", conversation.getTitle(),
                       "messagesRemoved", messages,
                       "sourcesRemoved", sources));
    }

    @Override
    public Conversation requireOwned(Long conversationId) {
        Conversation conversation = getById(conversationId);
        if (conversation == null) {
            throw new BizException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        // 不属于当前用户时也报「不存在」，不报 403 ——
        // 报 403 等于告诉调用方「这个 id 存在，只是不归你」
        if (!Objects.equals(conversation.getUserId(), SecurityUtils.getUserId())) {
            log.warn("用户 {} 试图访问不属于自己的会话 {}（属于用户 {}）",
                    SecurityUtils.getUserId(), conversationId, conversation.getUserId());
            throw new BizException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    // ---------- 内部 ----------

    /**
     * 给助手消息补上引用来源。
     * <p>
     * 一次查完所有引用再分组，不逐条查 —— 一个会话几十条消息，逐条查就是几十次 SQL。
     */
    private void fillSources(List<MessageVO> messages) {
        List<Long> assistantIds = messages.stream()
                .filter(m -> ChatConstants.ROLE_ASSISTANT.equals(m.getRole()))
                .map(m -> Long.valueOf(m.getMessageId()))
                .toList();

        if (assistantIds.isEmpty()) {
            return;
        }

        Map<Long, List<ChatSource>> grouped = sourceMapper.selectList(
                        Wrappers.<ChatSource>lambdaQuery().in(ChatSource::getMessageId, assistantIds))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(ChatSource::getMessageId));

        for (MessageVO message : messages) {
            List<ChatSource> sources = grouped.get(Long.valueOf(message.getMessageId()));
            if (sources != null && !sources.isEmpty()) {
                message.setSources(sources.stream()
                        .map(s -> SourceVO.of(s, s.getSourceTitle()))
                        .toList());
            }
        }
    }
}
