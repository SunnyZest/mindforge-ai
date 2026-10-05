package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改知识库。
 * <p>
 * 字段可空，传 null 表示不修改该字段。
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "修改知识库")
@Data
public class KnowledgeBaseUpdateDTO {

    @Schema(description = "知识库名称")
    @Size(max = 100, message = "知识库名称最长 100 个字符")
    private String name;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述最长 500 个字符")
    private String description;

    @Schema(description = "状态：1 启用 / 0 禁用")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
