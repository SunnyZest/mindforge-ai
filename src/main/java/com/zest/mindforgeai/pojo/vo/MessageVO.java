package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.ChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条对话消息。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "对话消息")
@Data
public class MessageVO {

    @Schema(description = "消息 id")
    private String messageId;

    @Schema(description = "角色：USER / ASSISTANT")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "模型名，仅助手消息有")
    private String modelName;

    @Schema(description = "回答引用的来源，仅助手消息有")
    private List<SourceVO> sources;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static MessageVO of(ChatMessage message) {
        MessageVO vo = new MessageVO();
        vo.setMessageId(String.valueOf(message.getId()));
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setModelName(message.getModelName());
        vo.setCreatedAt(message.getCreatedAt());
        return vo;
    }
}
