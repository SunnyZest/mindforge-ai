package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量更新状态入参。
 *
 * @author crx
 * @since 2026-10-11
 */
@Schema(description = "批量更新状态请求")
@Data
public class BatchStatusDTO {

    @Schema(description = "ID 列表")
    @NotEmpty(message = "ID 列表不能为空")
    @Size(max = 100, message = "单次批量操作最多支持 100 条")
    private List<Long> ids;

    @Schema(description = "目标状态：0 禁用，1 启用")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
