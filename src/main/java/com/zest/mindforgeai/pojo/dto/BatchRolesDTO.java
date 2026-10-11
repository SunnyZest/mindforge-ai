package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量分配角色入参。
 *
 * @author crx
 * @since 2026-10-11
 */
@Schema(description = "批量分配角色请求")
@Data
public class BatchRolesDTO {

    @Schema(description = "用户 ID 列表")
    @NotEmpty(message = "用户 ID 列表不能为空")
    @Size(max = 100, message = "单次批量操作最多支持 100 条")
    private List<Long> ids;

    @Schema(description = "角色 ID 列表（传空数组表示清空所有角色）")
    @NotNull(message = "角色 ID 列表不能为空")
    private List<Long> roleIds;
}
