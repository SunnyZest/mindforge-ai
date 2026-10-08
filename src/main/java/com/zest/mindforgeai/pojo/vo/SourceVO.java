package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.ChatSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 回答的引用来源，对齐文档 §十八。
 * <p>
 * 前端点引用时拿 {@code chunkId} 调 {@code GET /api/app/chunks/{id}} 取原文。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "引用来源")
@Data
public class SourceVO {

    @Schema(description = "文档 id")
    private String documentId;

    @Schema(description = "文档名 —— 展示「来自哪份文档」")
    private String documentName;

    @Schema(description = "分块 id —— 取原文用")
    private String chunkId;

    @Schema(description = "页码，仅 PDF 有值")
    private Integer pageNo;

    @Schema(description = "相似度得分")
    private Double score;

    public static SourceVO of(ChatSource source, String documentName) {
        SourceVO vo = new SourceVO();
        vo.setDocumentId(String.valueOf(source.getDocumentId()));
        vo.setDocumentName(documentName);
        vo.setChunkId(String.valueOf(source.getChunkId()));
        vo.setPageNo(source.getPageNo());
        vo.setScore(source.getRelevanceScore() == null
                ? null : source.getRelevanceScore().doubleValue());
        return vo;
    }
}
