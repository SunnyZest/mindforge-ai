package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * RAG 检索结果。
 *
 * @author crx
 * @since 2026-10-11
 */
@Schema(description = "RAG 检索结果")
@Data
public class RagSearchVO {

    @Schema(description = "检索到的片段，按相关性从高到低")
    private List<SourceVO> results;

    @Schema(description = "本次结果是否真的经过重排序 —— 请求了重排（enableRerank=true）"
            + "但 Python 侧没配 RERANK 模型或调用失败时会降级回向量序并返回 false，如实告知")
    private Boolean reranked;
}
