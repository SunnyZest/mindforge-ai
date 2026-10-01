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
 * 文档分块表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("document_chunk")
@Schema( description="DocumentChunk对象,文档分块表")
public class DocumentChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "与用户表的id字段关联")
    private Long userId;

    @Schema(description = "与个人知识库表的id字段关联")
    private Long knowledgeBaseId;

    @Schema(description = "与知识库文档表的id字段关联")
    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private Integer tokenCount;

    private Integer pageNo;

    private String vectorId;

    private String metadataJson;

    private LocalDateTime createdAt;


}
