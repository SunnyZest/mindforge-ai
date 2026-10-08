package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端文档列表项。
 * <p>
 * 与用户端的 {@link DocumentVO} 的差别：多了<b>归属人</b>和<b>所属知识库名称</b>。
 * 管理员是按「谁上传的 / 在哪个库里」来找文档的，用户端不需要（自己的文档自己清楚）。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "管理端文档列表项")
@Data
public class AdminDocumentVO {

    @Schema(description = "文档 id（字符串，避免 JS 精度丢失）")
    private String documentId;

    @Schema(description = "所属知识库 id")
    private String knowledgeBaseId;

    @Schema(description = "所属知识库名称")
    private String knowledgeBaseName;

    @Schema(description = "上传者用户 id")
    private String userId;

    @Schema(description = "上传者用户名")
    private String username;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件类型（扩展名大写）")
    private String fileType;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "聚合后的总状态，见 DocumentStatusUtils")
    private String status;

    @Schema(description = "解析状态")
    private String parseStatus;

    @Schema(description = "分块状态")
    private String chunkStatus;

    @Schema(description = "向量化状态")
    private String vectorStatus;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "页数")
    private Integer pageCount;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;
}
