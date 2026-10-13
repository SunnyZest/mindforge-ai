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
 * 对话表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("conversation")
@Schema( description="Conversation对象,对话表")
public class Conversation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "与用户表的id字段关联")
    private Long userId;

    @Schema(description = "与个人知识库表的id字段关联")
    private Long knowledgeBaseId;

    private String title;

    private String modelName;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;


}
