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
 * 角色表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_role")
@Schema( description="SysRole对象,角色表")
public class SysRole implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "不同角色(role)id")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "角色(role),用户、管理员")
    private String name;

    @Schema(description = "角色对应code")
    private String code;

    @Schema(description = "1启用 0禁用")
    private Integer status;

    /**
     * 角色层级，数值越大权限越高。
     * <p>
     * 用于<b>垂直越权防护</b>：不能操作层级 ≥ 自己的角色或用户。
     * 比如 ADMIN（50）不能禁用 SUPER_ADMIN（100）的账号、
     * 也不能给用户分配 SUPER_ADMIN 角色。
     * <p>
     * 内置层级：USER = 10、ADMIN = 50、SUPER_ADMIN = 100。
     */
    @Schema(description = "角色层级，越大越高")
    private Integer level;

    @Schema(description = "角色描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;

    @Schema(description = "角色是否已删除(逻辑)")
    private Integer deleted;


}
