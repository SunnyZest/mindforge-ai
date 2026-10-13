package com.zest.mindforgeai.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 在线精修 / 采纳 AI 优化建议后更新简历内容。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "更新简历内容请求")
@Data
public class ResumeUpdateDTO {

    @Schema(description = "简历名称")
    @Size(max = 100, message = "简历名称最长 100 个字符")
    private String name;

    @Schema(description = "目标职位")
    @Size(max = 128, message = "目标职位最长 128 个字符")
    private String targetPosition;

    @Schema(description = "个人总结摘要")
    @Size(max = 2000, message = "简历摘要最长 2000 个字符")
    private String summary;

    @Schema(description = "简历原文全文（采纳 AI 改写或手工精修后的全文）")
    @Size(max = 50000, message = "简历全文最长 50000 个字符")
    private String rawContent;

    @Schema(description = "结构化简历 JSON 对象（用于 A4 简历排版与导出 PDF）")
    private JsonNode structuredJson;
}
