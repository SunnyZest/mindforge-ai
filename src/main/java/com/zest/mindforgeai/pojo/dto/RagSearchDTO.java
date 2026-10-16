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

    /**
     * 知识库 id。
     * <p>
     * <b>可以不传</b> —— 不传表示「搜我自己的全部知识库 + 官方智库」。
     * 这是搜索页的主用法：用户不该为了搜一句话先想清楚「这内容在哪个库」。
     * 传了就按传的那个库（仍然会自动并上官方智库，即双轨）。
     * <p>
     * ⚠️ 传了就必须是<b>我有权访问的</b>库，否则 404（不区分「不存在」和「不是你的」）。
     */
    @Schema(description = "知识库 id；不传 = 搜索我的全部知识库 + 官方智库")
    @Size(max = 32, message = "知识库 ID 长度不合法")
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
