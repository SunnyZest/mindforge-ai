package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 认领到的待处理文档 —— 返回给 Python AI 服务。
 * <p>
 * 这是 {@code POST /internal/documents/claim} 的响应元素。
 * Python 拿到后直接下载 {@code downloadUrl} 开始处理，不需要知道 OSS 的任何配置。
 *
 * @author crx
 * @since 2026-10-06
 */
@Schema(description = "待处理文档（内部接口）")
@Data
public class DocumentClaimVO {

    @Schema(description = "文档 id")
    private String documentId;

    @Schema(description = "所属知识库 id —— 分块/向量化时要写进 metadata，检索时按它过滤")
    private String knowledgeBaseId;

    @Schema(description = "归属用户 id")
    private String userId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "文件类型：PDF / DOCX / TXT / MD / HTML")
    private String fileType;

    @Schema(description = "文件字节数")
    private Long fileSize;

    /**
     * 预签名下载地址（有时效）。
     * <p>
     * Python 用它直接下载，<b>不经过 Java</b> —— 也就不用给 Python 配 OSS 的 AK/SK。
     */
    @Schema(description = "预签名下载地址")
    private String downloadUrl;
}
