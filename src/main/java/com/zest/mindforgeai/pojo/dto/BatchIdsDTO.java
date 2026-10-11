package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量 ID 入参。
 *
 * @author crx
 * @since 2026-10-11
 */
@Schema(description = "批量 ID 操作请求")
@Data
public class BatchIdsDTO {

    @Schema(description = "ID 列表")
    @NotEmpty(message = "ID 列表不能为空")
    @Size(max = 100, message = "单次批量操作最多支持 100 条")
    private List<Long> ids;
}
