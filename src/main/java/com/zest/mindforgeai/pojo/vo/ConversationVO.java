package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.Conversation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "会话")
@Data
public class ConversationVO {

    @Schema(description = "会话 id")
    private String conversationId;

    @Schema(description = "关联的知识库 id")
    private String knowledgeBaseId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "使用的模型名")
    private String modelName;

    @Schema(description = "消息条数")
    private Long messageCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    public static ConversationVO of(Conversation conversation, Long messageCount) {
        ConversationVO vo = new ConversationVO();
        vo.setConversationId(String.valueOf(conversation.getId()));
        vo.setKnowledgeBaseId(conversation.getKnowledgeBaseId() == null
                ? null : String.valueOf(conversation.getKnowledgeBaseId()));
        vo.setTitle(conversation.getTitle());
        vo.setModelName(conversation.getModelName());
        vo.setMessageCount(messageCount);
        vo.setCreatedAt(conversation.getCreatedAt());
        vo.setUpdatedAt(conversation.getUpdatedAt());
        return vo;
    }
}
