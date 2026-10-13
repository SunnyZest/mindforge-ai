package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 个人知识库
 *
 * @author crx
 * @since 2026-10-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("knowledge_base")
@Schema(description = "KnowledgeBase对象,个人知识库表")
public class KnowledgeBase implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属用户 id，逻辑关联 user.id")
    private Long userId;

    @Schema(description = "知识库名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态：1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "文档数量（冗余计数，避免每次 COUNT）")
    private Integer documentCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "是否系统官方全局智库：0 否 / 1 是")
    private Integer isSystem;

    @Schema(description = "逻辑删除：0 未删除 / 1 已删除")
    private Integer deleted;
}
