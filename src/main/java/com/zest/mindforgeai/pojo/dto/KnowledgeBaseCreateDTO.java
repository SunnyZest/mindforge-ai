package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建知识库，文档 §十五
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "创建知识库")
@Data
public class KnowledgeBaseCreateDTO {

    @Schema(description = "知识库名称", example = "Java 学习知识库")
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 100, message = "知识库名称最长 100 个字符")
    private String name;

    @Schema(description = "描述", example = "Java、Spring Boot、MySQL")
    @Size(max = 500, message = "描述最长 500 个字符")
    private String description;
}
