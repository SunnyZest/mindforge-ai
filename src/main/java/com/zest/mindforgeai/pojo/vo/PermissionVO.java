package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.SysPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 权限项
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "权限项")
@Data
public class PermissionVO {

    @Schema(description = "权限 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "权限名称")
    private String name;

    @Schema(description = "权限码，如 admin:user:list")
    private String code;

    @Schema(description = "类型")
    private String type;

    @Schema(description = "接口路径")
    private String path;

    @Schema(description = "HTTP 方法")
    private String method;

    @Schema(description = "描述")
    private String description;

    public static PermissionVO of(SysPermission p) {
        PermissionVO vo = new PermissionVO();
        vo.setId(p.getId() == null ? null : String.valueOf(p.getId()));
        vo.setName(p.getName());
        vo.setCode(p.getCode());
        vo.setType(p.getType());
        vo.setPath(p.getPath());
        vo.setMethod(p.getMethod());
        vo.setDescription(p.getDescription());
        return vo;
    }
}
