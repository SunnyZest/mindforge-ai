package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建会话请求，对齐文档 §十八。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "新建会话")
@Data
public class CreateConversationDTO {

    @Schema(description = "关联的知识库 id（可选，为空表示通用 AI 对话）")
    private String knowledgeBaseId;

    @Schema(description = "会话标题")
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题不能超过 255 字")
    private String title;
}
