package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理端启用 / 禁用用户
 *
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "启用或禁用用户")
@Data
public class UserStatusDTO {

    @Schema(description = "状态：1 启用 / 0 禁用", example = "0")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
