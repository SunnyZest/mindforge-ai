package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改角色。
 * <p>
 * <b>刻意不提供 code 字段</b>：权限判定和代码里的 {@code hasRole('ADMIN')} 都依赖 code，
 * 允许修改会静默破坏授权。要换 code 只能新建角色。
 * <p>
 * 所有字段可空，传 null 表示不修改该字段。
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "修改角色")
@Data
public class RoleUpdateDTO {

    @Schema(description = "角色名称")
    @Size(max = 64, message = "角色名称最长 64 个字符")
    private String name;

    @Schema(description = "描述")
    @Size(max = 255, message = "描述最长 255 个字符")
    private String description;

    @Schema(description = "状态：1 启用 / 0 禁用")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
