package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量写入文档分块 —— Python AI 服务调用。
 *
 * @author crx
 * @since 2026-10-06
 */
@Schema(description = "批量写入文档分块")
@Data
public class ChunkBatchDTO {

    @Schema(description = "分块列表")
    @NotEmpty(message = "分块列表不能为空")
    @Valid
    private List<Item> chunks;

    @Schema(description = "单个分块")
    @Data
    public static class Item {

        @Schema(description = "分块序号，同一文档内从 0 开始且唯一")
        private Integer chunkIndex;

        @Schema(description = "分块正文")
        private String content;

        @Schema(description = "token 数")
        private Integer tokenCount;

        @Schema(description = "所在页码，可为空")
        private Integer pageNo;

        @Schema(description = "向量库里的 id，便于将来按 chunk 反查向量")
        private String vectorId;

        @Schema(description = "附加元数据（JSON 字符串），比如章节标题")
        private String metadataJson;
    }
}
