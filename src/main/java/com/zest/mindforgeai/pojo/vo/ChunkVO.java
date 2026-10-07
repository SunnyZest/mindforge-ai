package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.entity.DocumentChunk;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文档分块。
 * <p>
 * 用途有两个：
 * <ol>
 *   <li><b>引用溯源</b> —— 用户在对话里点某条引用，要看到那段原文，
 *       以及它来自哪个文档（{@code documentName}）。</li>
 *   <li><b>管理端排查</b> —— 文档检索不到时，管理员需要看分块切得对不对。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-07
 */
@Schema(description = "文档分块")
@Data
public class ChunkVO {

    @Schema(description = "分块 id（字符串，避免 JS 精度丢失）")
    private String chunkId;

    @Schema(description = "所属文档 id")
    private String documentId;

    @Schema(description = "所属文档的文件名 —— 引用溯源时展示「来自哪个文档」")
    private String documentName;

    @Schema(description = "所属知识库 id")
    private String knowledgeBaseId;

    @Schema(description = "分块序号，从 0 开始")
    private Integer chunkIndex;

    @Schema(description = "分块正文")
    private String content;

    @Schema(description = "估算的 token 数（仅用于展示，不影响检索）")
    private Integer tokenCount;

    @Schema(description = "所在页码，仅 PDF 有值")
    private Integer pageNo;

    /**
     * 组装 VO。
     *
     * @param chunk 分块实体
     * @param doc   所属文档 —— 调用方保证非空（文档被删时不该看到它的分块）
     */
    public static ChunkVO of(DocumentChunk chunk, Document doc) {
        ChunkVO vo = new ChunkVO();
        vo.setChunkId(String.valueOf(chunk.getId()));
        vo.setDocumentId(String.valueOf(chunk.getDocumentId()));
        vo.setDocumentName(doc == null ? null : doc.getFileName());
        vo.setKnowledgeBaseId(chunk.getKnowledgeBaseId() == null
                ? null : String.valueOf(chunk.getKnowledgeBaseId()));
        vo.setChunkIndex(chunk.getChunkIndex());
        vo.setContent(chunk.getContent());
        vo.setTokenCount(chunk.getTokenCount());
        vo.setPageNo(chunk.getPageNo());
        return vo;
    }
}
