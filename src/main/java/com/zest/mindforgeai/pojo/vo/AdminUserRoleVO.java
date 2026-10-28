package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端用户详情里的「角色项」。
 * <p>
 * 与 {@link UserRoleItemVO} 的区别：那个只带 userId + roleCode，是列表页批量查询后
 * 在代码里分组用的内部结构；这里带 roleId 和 roleName，是给管理员看的 ——
 * 详情页要能显示「这个人是 管理员（ADMIN）」，而不只是一个 code。
 *
 * @author crx
 * @since 2026-10-28
 */
@Schema(description = "管理端用户详情-角色项")
@Data
public class AdminUserRoleVO {

    @Schema(description = "角色 id（字符串，避免 JS 精度丢失）")
    private String roleId;

    @Schema(description = "角色 code，如 ADMIN")
    private String roleCode;

    @Schema(description = "角色名称，如 管理员")
    private String roleName;
}
