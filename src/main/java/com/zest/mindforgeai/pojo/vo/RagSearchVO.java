package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * RAG 检索结果。
 * <p>
 * ⚠️ {@code results} 用的是 {@link SearchHitVO}（<b>带正文</b>），不是聊天引用那套
 * {@link SourceVO}（只有文件名+页码）—— 搜索页的结果主体就是正文本身。
 *
 * @author crx
 * @since 2026-10-11
 */
@Schema(description = "RAG 检索结果")
@Data
public class RagSearchVO {

    @Schema(description = "检索到的片段，按相关性从高到低")
    private List<SearchHitVO> results;

    @Schema(description = "本次结果是否真的经过重排序 —— 请求了重排（enableRerank=true）"
            + "但 Python 侧没配 RERANK 模型或调用失败时会降级回向量序并返回 false，如实告知")
    private Boolean reranked;

    @Schema(description = "本次实际覆盖的知识库数量 —— 跨库检索时前端可提示「已在 N 个库中检索」")
    private Integer searchedKbCount;
}
