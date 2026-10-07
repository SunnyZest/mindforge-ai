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
 * 知识库文档表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("document")
@Schema( description="Document对象,知识库文档表")
public class Document implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "与用户表的id字段关联")
    private Long userId;

    @Schema(description = "与个人知识库表的id字段关联")
    private Long knowledgeBaseId;

    @Schema(description = "与文件对象存储元数据表的id字段关联")
    private Long fileId;

    private String fileName;

    private String fileType;

    private String fileUrl;

    private Long fileSize;

    private String contentHash;

    private String parseStatus;

    private String chunkStatus;

    private String vectorStatus;

    private String errorMessage;

    private Integer pageCount;

    /**
     * 向量是否已从向量库清除：0=未清 1=已清。
     * <p>
     * 文档删除后，数据库记录是逻辑删除，但向量库里的向量不会自动消失。
     * AI 服务会定期拉「已删除但未清向量」的文档，清完后把这个字段置 1。
     */
    private Integer vectorPurged;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;


}
