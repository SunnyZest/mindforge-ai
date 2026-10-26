package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库回收站的 payload 快照。
 *
 * <p>★ 知识库的删除**几乎全是逻辑删**（`knowledge_base` / `document` / `conversation` 都有
 * deleted 列，`document_chunk` 压根不动），所以不需要存行内容，只要记住
 * **「删除那一刻被级联删掉了哪些 id」**，恢复时精确改回 0。
 *
 * <p>⚠️ 不能按 `knowledge_base_id` 全量捞 —— 那样会把用户之前单独删过的文档也一起复活，
 * 他会看到一堆早就不想要的东西。
 *
 * <p>{@code fileIds} 单独记：`deleteCascade` 会对它们做 `sys_file` 软删，
 * 恢复时要清掉 `deleted_at`，否则 `FilePurgeTask` 会按保留期把 OSS 对象真删掉。
 *
 * @author crx
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识库回收站快照")
public class KnowledgeBaseRecyclePayload {

    @Schema(description = "删除时被级联逻辑删掉的文档 id")
    private List<Long> documentIds;

    @Schema(description = "删除时被级联逻辑删掉的对话 id")
    private List<Long> conversationIds;

    @Schema(description = "对应的原始文件 id（用于恢复 sys_file）")
    private List<Long> fileIds;
}
