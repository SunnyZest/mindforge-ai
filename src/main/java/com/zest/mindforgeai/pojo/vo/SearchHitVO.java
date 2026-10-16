package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 语义检索命中的片段（搜索页用）。
 * <p>
 * ★ <b>为什么不复用 {@link SourceVO}</b>：那个是给聊天当「引用标记」用的 ——
 * 回答已经由模型生成，来源只需要文件名和页码让人能溯源，<b>不需要正文</b>。
 * 而搜索页的结果本身就是正文，没正文等于没结果。
 * 另外一个差别：搜索可以跨多个知识库，所以要带上来源知识库的名字。
 *
 * @author crx
 * @since 2026-10-16
 */
@Schema(description = "语义检索命中的片段")
@Data
public class SearchHitVO {

    @Schema(description = "所属文档 id")
    private String documentId;

    @Schema(description = "文档名")
    private String documentName;

    @Schema(description = "所属知识库 id")
    private String knowledgeBaseId;

    @Schema(description = "所属知识库名 —— 跨库检索时用来标明「这条来自哪个库」")
    private String knowledgeBaseName;

    @Schema(description = "是否来自官方智库（前端可以给个标记）")
    private Boolean fromSystemKb;

    @Schema(description = "分块 id —— 取完整原文用")
    private String chunkId;

    @Schema(description = "分块在文档里的序号")
    private Integer chunkIndex;

    @Schema(description = "页码（当前与 chunkIndex 同值，PDF 才有意义）")
    private Integer pageNo;

    @Schema(description = "★ 片段正文 —— 搜索结果的主体")
    private String content;

    @Schema(description = "相关度得分（0~1，开启重排时是 rerank 分数；两种量纲不同但都在 0~1，展示不用区分）")
    private Double score;
}
