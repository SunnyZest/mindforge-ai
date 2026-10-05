package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.dto.RoleCreateDTO;
import com.zest.mindforgeai.pojo.dto.RoleUpdateDTO;
import com.zest.mindforgeai.pojo.entity.SysRole;
import com.zest.mindforgeai.pojo.vo.PermissionVO;
import com.zest.mindforgeai.pojo.vo.RoleVO;

import java.util.List;

/**
 * 角色管理服务（管理端视角）。
 * <p>
 * 与 {@code admin:role:*} 权限码一一对应，权限由 Controller 的 {@code @PreAuthorize} 把关。
 *
 * @author crx
 * @since 2026-10-05
 */
public interface RoleService extends IService<SysRole> {

    /** 角色列表，含每个角色已绑定的权限数量 */
    List<RoleVO> listRoles();

    /** 创建角色 */
    RoleVO createRole(RoleCreateDTO dto);

    /** 修改角色（名称 / 描述 / 状态；code 不允许改） */
    void updateRole(Long roleId, RoleUpdateDTO dto);

    /** 删除角色（内置角色不可删） */
    void deleteRole(Long roleId);

    /** 查角色已绑定的权限 */
    List<PermissionVO> listRolePermissions(Long roleId);

    /** 全量覆盖角色的权限（不是追加） */
    void assignPermissions(Long roleId, List<Long> permissionIds);
}
