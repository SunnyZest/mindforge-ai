package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 给用户分配角色（全量覆盖，不是追加）。
 *
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "分配角色")
@Data
public class AssignRolesDTO {

    @Schema(description = "角色 id 列表；传空数组表示清空该用户所有角色",
            example = "[\"2\"]")
    @NotNull(message = "roleIds 不能为空（可为空数组）")
    private List<Long> roleIds;
}
