package com.zest.mindforgeai.pojo.dto;

import com.zest.mindforgeai.pojo.entity.ChatMessage;
import com.zest.mindforgeai.pojo.entity.ChatSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话回收站的 payload 快照。
 *
 * <p>★ 对话是三个二期资源里快照最大的一个（一次对话几十条消息 + 每条的引用来源），
 * 但仍然值得存：用户误删一次对话，等于把整段问答记录和引用依据全丢了。
 *
 * <p>⚠️ 恢复时 {@code chat_source.message_id} 必须**重映射** ——
 * 消息重建后拿到的是新雪花 id，直接沿用旧 id 会指向不存在的消息（孤儿引用）。
 *
 * @author crx
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "对话回收站快照")
public class ConversationRecyclePayload {

    @Schema(description = "消息（删除时被物理删掉的行）")
    private List<ChatMessage> messages;

    @Schema(description = "消息引用的来源（同样被物理删）")
    private List<ChatSource> sources;
}
