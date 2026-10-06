package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档处理状态（文档 §十六的 {@code GET /documents/{id}/status}）。
 * <p>
 * 刻意做得比 {@link DocumentVO} 轻 —— 这个接口是给前端<b>轮询</b>用的，
 * 文档解析/向量化可能跑几十秒，前端会隔几秒查一次。
 *
 * @author crx
 * @since 2026-10-06
 */
@Schema(description = "文档处理状态")
@Data
public class DocumentStatusVO {

    @Schema(description = "文档 id")
    private String documentId;

    /**
     * 聚合状态：UPLOADED / PARSING / PARSED / CHUNKING / CHUNKED /
     * EMBEDDING / VECTOR_INDEXED / COMPLETED / FAILED
     */
    @Schema(description = "聚合状态")
    private String status;

    @Schema(description = "解析状态")
    private String parseStatus;

    @Schema(description = "分块状态")
    private String chunkStatus;

    @Schema(description = "向量化状态")
    private String vectorStatus;

    @Schema(description = "失败原因，仅 FAILED 时有值")
    private String errorMessage;

    @Schema(description = "最后更新时间，前端可据此判断是否还在推进")
    private LocalDateTime updatedAt;
}
