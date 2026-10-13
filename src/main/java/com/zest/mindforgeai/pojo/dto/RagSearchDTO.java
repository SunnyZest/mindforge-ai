package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 纯检索请求（不调用 LLM），对齐文档 §十八。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "RAG 检索")
@Data
public class RagSearchDTO {

    @Schema(description = "知识库 id")
    @NotBlank(message = "知识库 id 不能为空")
    private String knowledgeBaseId;

    @Schema(description = "检索内容")
    @NotBlank(message = "检索内容不能为空")
    @Size(max = 2000, message = "检索内容不能超过 2000 个字符")
    private String query;

    @Schema(description = "返回片段数，默认 8")
    @Min(value = 1, message = "topK 至少为 1")
    @Max(value = 50, message = "topK 最多为 50")
    private Integer topK = 8;

    @Schema(description = "是否重排序（未配置 RERANK 模型或调用失败时 Python 侧降级回向量序，响应 reranked 字段如实反映）")
    private Boolean enableRerank = false;
}
