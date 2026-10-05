package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 知识库统计，文档 §十五
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "知识库统计")
@Data
public class KnowledgeBaseStatsVO {

    @Schema(description = "未删除的文档总数")
    private Long documentCount;

    @Schema(description = "分块总数")
    private Long chunkCount;

    @Schema(description = "解析完成的文档数（parse_status = SUCCESS）")
    private Long completedDocuments;

    @Schema(description = "解析失败的文档数（parse_status = FAILED）")
    private Long failedDocuments;
}
