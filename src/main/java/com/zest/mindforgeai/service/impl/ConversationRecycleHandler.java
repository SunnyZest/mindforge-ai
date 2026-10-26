package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.mapper.ChatMessageMapper;
import com.zest.mindforgeai.mapper.ChatSourceMapper;
import com.zest.mindforgeai.pojo.dto.ConversationRecyclePayload;
import com.zest.mindforgeai.pojo.entity.ChatMessage;
import com.zest.mindforgeai.pojo.entity.ChatSource;
import com.zest.mindforgeai.service.RecycleRestoreHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话的回收站恢复策略。
 *
 * <p>删对话时：{@code conversation} 逻辑删（改回 0 即可），
 * {@code chat_message} / {@code chat_source} 是**物理删** —— 必须快照重建。
 *
 * <p>★ <b>这里有个别处没有的难点</b>：{@code chat_source} 通过 {@code message_id}
 * 挂在消息上。消息重建后拿到的是**新雪花 id**，所以恢复来源时必须做一次
 * 旧 id → 新 id 的映射，否则引用会指向不存在的消息（孤儿引用，前端展示时会缺引用）。
 *
 * @author crx
 * @since 2026-10-26
 */
@Slf4j
@Component
public class ConversationRecycleHandler implements RecycleRestoreHandler {

    private final ChatMessageMapper messageMapper;
    private final ChatSourceMapper sourceMapper;
    private final ObjectMapper objectMapper;

    public ConversationRecycleHandler(ChatMessageMapper messageMapper,
                                      ChatSourceMapper sourceMapper,
                                      ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.sourceMapper = sourceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public RecycleResourceType type() {
        return RecycleResourceType.CONVERSATION;
    }

    @Override
    public String capturePayload(Long conversationId) {
        List<ChatMessage> messages = messageMapper.selectList(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getConversationId, conversationId));

        // 引用要按 message_id 查 —— 消息为空时不能拼 IN ()（会 SQL 语法错误）
        List<Long> messageIds = messages.stream().map(ChatMessage::getId).toList();
        List<ChatSource> sources = messageIds.isEmpty()
                ? List.of()
                : sourceMapper.selectList(Wrappers.<ChatSource>lambdaQuery()
                        .in(ChatSource::getMessageId, messageIds));

        try {
            return objectMapper.writeValueAsString(new ConversationRecyclePayload(messages, sources));
        } catch (JsonProcessingException e) {
            log.error("采集对话回收站快照失败 conversationId={}，恢复后消息与引用会缺失", conversationId, e);
            return null;
        }
    }

    @Override
    public boolean restoreRelated(Long conversationId, String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            log.warn("对话回收站记录没有 payload，只能恢复主表 conversationId={}（消息会缺失）", conversationId);
            return false;
        }

        ConversationRecyclePayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, ConversationRecyclePayload.class);
        } catch (JsonProcessingException e) {
            log.error("解析对话回收站快照失败 conversationId={}，只恢复主表", conversationId, e);
            return false;
        }

        // ① 先重建消息，并记下「旧 id → 新 id」的映射
        Map<Long, Long> idMap = new HashMap<>();
        List<ChatMessage> messages = payload.getMessages();
        if (messages != null) {
            for (ChatMessage message : messages) {
                Long oldId = message.getId();
                message.setId(null);          // 用新雪花，避免旧 id 被别处引用造成幽灵引用
                messageMapper.insert(message);
                idMap.put(oldId, message.getId());
            }
        }

        // ② 再重建引用，message_id 必须换成新 id
        List<ChatSource> sources = payload.getSources();
        if (sources != null) {
            int skipped = 0;
            for (ChatSource source : sources) {
                Long newMessageId = idMap.get(source.getMessageId());
                if (newMessageId == null) {
                    // 快照本身不一致（来源指向的消息不在里面）—— 跳过，别插孤儿
                    skipped++;
                    continue;
                }
                source.setId(null);
                source.setMessageId(newMessageId);
                sourceMapper.insert(source);
            }
            if (skipped > 0) {
                log.warn("对话恢复时有 {} 条引用找不到对应消息，已跳过 conversationId={}", skipped, conversationId);
            }
        }

        log.info("对话关联数据已重建 conversationId={} 消息={} 引用={}",
                conversationId,
                messages == null ? 0 : messages.size(),
                sources == null ? 0 : sources.size());
        return true;
    }
}
