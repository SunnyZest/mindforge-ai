package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 看板拖拽/快捷切换投递阶段请求。
 *
 * @author crx
 * @since 2026-10-13
 */
@Schema(description = "更新职位投递阶段")
@Data
public class JobStageUpdateDTO {

    @Schema(description = "目标阶段：WISHLIST / APPLIED / SCREENING / INTERVIEW_1 / INTERVIEW_2 / HR / OFFER / REJECTED")
    @NotBlank(message = "投递阶段不能为空")
    @Pattern(
            regexp = "^(WISHLIST|APPLIED|SCREENING|INTERVIEW_1|INTERVIEW_2|HR|OFFER|REJECTED)$",
            message = "不支持的投递阶段"
    )
    private String stage;
}
