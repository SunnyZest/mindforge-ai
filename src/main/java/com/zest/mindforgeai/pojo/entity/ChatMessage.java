package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 聊天消息表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("chat_message")
@Schema(description="ChatMessage对象,聊天消息表")
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "与对话表的id字段关联")
    private Long conversationId;

    @Schema(description = "与用户表的id字段关联")
    private Long userId;

    @Schema(description = "SYSTEM/USER/ASSISTANT")
    private String role;

    private String content;

    private Integer tokenCount;

    private String modelName;

    private Integer promptTokens;

    private Integer completionTokens;

    private String finishReason;

    private LocalDateTime createdAt;

    /**
     * 用户反馈：UP 有帮助 / DOWN 没帮助。
     * <p>
     * 只对助手消息有意义 —— 用户给自己的提问打分没意义。
     * 一条消息只有一个反馈，重复提交覆盖。
     */
    private String rating;

    /** 反馈的补充说明 */
    private String feedbackReason;

    /** 反馈时间 */
    private LocalDateTime feedbackAt;
}
