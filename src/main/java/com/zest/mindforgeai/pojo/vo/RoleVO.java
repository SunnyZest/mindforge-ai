package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.SysRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色项
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "角色项")
@Data
public class RoleVO {

    @Schema(description = "角色 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "角色名称")
    private String name;

    @Schema(description = "角色 code，如 ADMIN")
    private String code;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态：1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "已绑定的权限数量")
    private long permissionCount;

    @Schema(description = "是否内置角色（内置角色不允许删除）")
    private boolean builtin;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static RoleVO of(SysRole role, long permissionCount, boolean builtin) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId() == null ? null : String.valueOf(role.getId()));
        vo.setName(role.getName());
        vo.setCode(role.getCode());
        vo.setDescription(role.getDescription());
        vo.setStatus(role.getStatus());
        vo.setPermissionCount(permissionCount);
        vo.setBuiltin(builtin);
        vo.setCreatedAt(role.getCreatedAt());
        return vo;
    }
}
