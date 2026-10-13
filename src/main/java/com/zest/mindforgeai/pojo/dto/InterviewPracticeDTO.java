package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 错题本单题重练请求。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "错题本单题重练请求")
@Data
public class InterviewPracticeDTO {

    @Schema(description = "重练回答内容")
    @NotBlank(message = "重练回答内容不能为空")
    @Size(max = 10000, message = "回答内容不能超过 10000 个字符")
    private String answer;
}
