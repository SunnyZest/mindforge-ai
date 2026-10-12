package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 网页 URL 导入请求
 *
 * @author crx
 * @since 2026-10-12
 */
@Schema(description = "网页 URL 导入请求")
@Data
public class ImportUrlDTO {

    @Schema(description = "网页地址", example = "https://example.com/article")
    @NotBlank(message = "网页 URL 不能为空")
    private String url;
}
