package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档项。
 * <p>
 * 字段名对齐文档 §十六（上传响应用的是 {@code documentId} / {@code fileName} / {@code status}）。
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "文档项")
@Data
public class DocumentVO {

    @Schema(description = "文档 id（字符串，避免 JS 精度丢失）")
    private String documentId;

    @Schema(description = "所属知识库 id")
    private String knowledgeBaseId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "文件类型：PDF / DOCX / TXT / MD / HTML")
    private String fileType;

    @Schema(description = "文件字节数")
    private Long fileSize;

    /**
     * 聚合状态，对齐文档 §十六 的状态序列：
     * UPLOADED → PARSING → PARSED → CHUNKING → CHUNKED → EMBEDDING → VECTOR_INDEXED → COMPLETED，失败为 FAILED。
     * <p>
     * 库里其实是 parse/chunk/vector 三个独立状态字段，这里聚合出一个对外的单一状态给前端用。
     */
    @Schema(description = "聚合状态")
    private String status;

    @Schema(description = "解析状态：PENDING / PARSING / SUCCESS / FAILED")
    private String parseStatus;

    @Schema(description = "分块状态：PENDING / CHUNKING / SUCCESS / FAILED")
    private String chunkStatus;

    @Schema(description = "向量化状态：PENDING / EMBEDDING / SUCCESS / FAILED")
    private String vectorStatus;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "页数")
    private Integer pageCount;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;

    /**
     * 预签名下载地址，1 小时有效。
     * <p>
     * ⚠️ <b>只有详情接口会填</b>（{@code DocumentServiceImpl.getDetail}）。列表接口这个字段恒为 null ——
     * 列表一页 20 条，每条都查一次 {@code sys_file} 就是 N+1，而列表页并不需要下载地址。
     * <p>
     * 为 null 的两种情况：文档没有关联文件（网页导入的没有 fileId）；或者生成签名时抛异常
     * （存储没配好）—— 后者刻意不上抛，用户这时更需要看到文档的解析状态。
     */
    @Schema(description = "预签名下载地址（1 小时有效）；网页导入的文档没有文件对象，为 null")
    private String downloadUrl;
}
