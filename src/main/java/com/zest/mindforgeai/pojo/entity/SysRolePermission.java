package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 角色权限关联表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_role_permission")
@Schema( description="SysRolePermission对象，角色权限关联表")
public class SysRolePermission implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "与角色表的id字段关联")
    private Long roleId;

    @Schema(description = "与权限表的id字段关联")
    private Long permissionId;

    private LocalDateTime createdAt;


}
