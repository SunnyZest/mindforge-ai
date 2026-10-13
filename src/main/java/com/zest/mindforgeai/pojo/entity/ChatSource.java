package com.zest.mindforgeai.pojo.entity;

import java.math.BigDecimal;
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
 * RAG引用来源表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("chat_source")
@Schema( description="ChatSource对象,RAG引用来源表")
public class ChatSource implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "外键聊天消息表")
    private Long messageId;

    @Schema(description = "文档表关联")
    private Long documentId;

    @Schema(description = "文档分块表")
    private Long chunkId;

    private Integer pageNo;

    private BigDecimal relevanceScore;

    private String sourceTitle;

    private LocalDateTime createdAt;


}
