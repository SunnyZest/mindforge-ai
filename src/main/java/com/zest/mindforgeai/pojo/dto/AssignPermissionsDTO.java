package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 给角色分配权限（全量覆盖，不是追加），文档 §十四
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "给角色分配权限")
@Data
public class AssignPermissionsDTO {

    @Schema(description = "权限 id 列表；传空数组表示清空该角色所有权限",
            example = "[\"1\", \"2\", \"3\"]")
    @NotNull(message = "permissionIds 不能为空（可为空数组）")
    private List<Long> permissionIds;
}
