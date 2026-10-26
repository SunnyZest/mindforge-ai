package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档回收站的 payload 快照。
 *
 * <p>★ <b>文档是三种资源里最简单的一个</b>：删除只做两件事 ——
 * {@code document} 逻辑删、{@code sys_file} 软删，**{@code document_chunk} 完全不动**
 * （代码注释：物理删代价大，留待后台清理）。
 *
 * <p>所以 payload 只需要 `fileId` 一个字段；甚至它丢了也能恢复 ——
 * `fileId` 在主表里，Handler 会回退去查。
 *
 * <p>真正的坑是**向量**：Python 会按 {@code deleted = 1 AND vector_purged = 0} 清 Chroma 里的向量。
 * 恢复时若 {@code vector_purged = 1}，文档能打开、正文也在，但**检索搜不到** ——
 * 那时标 {@code vector_status = PENDING} 触发重新处理，并让上层返回警告。
 *
 * @author crx
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档回收站快照")
public class DocumentRecyclePayload {

    @Schema(description = "原始文件 id，用于恢复 sys_file")
    private Long fileId;
}
