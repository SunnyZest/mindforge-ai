package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 「角色 → 权限数量」的一行结果，仅用于批量统计后在代码里取值，
 * 不直接返回给前端。
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "角色权限数量")
@Data
public class RolePermissionCountVO {

    private Long roleId;

    private Long cnt;
}
