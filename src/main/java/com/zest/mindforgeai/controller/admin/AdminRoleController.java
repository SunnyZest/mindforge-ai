package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.AssignPermissionsDTO;
import com.zest.mindforgeai.pojo.dto.RoleCreateDTO;
import com.zest.mindforgeai.pojo.dto.RoleUpdateDTO;
import com.zest.mindforgeai.pojo.vo.PermissionVO;
import com.zest.mindforgeai.pojo.vo.RoleVO;
import com.zest.mindforgeai.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端角色管理，对外路径 {@code /api/admin/roles/**}（文档 §十四）。
 * <p>
 * 两层防护：{@code /admin/**} 的 URL 规则要求 ADMIN / SUPER_ADMIN，
 * 本类的 {@code @PreAuthorize} 再按权限码细分。
 * <p>
 * 注意：{@code admin:role:*} 权限码<b>只给了 SUPER_ADMIN</b>（ADMIN 一条都没有），
 * 所以 ADMIN 访问这里会被 {@code @PreAuthorize} 拦成 403 —— 这是有意的设计。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Tag(name = "管理端-角色管理")
@RestController
@RequestMapping("/admin/roles")
public class AdminRoleController {

    private final RoleService roleService;

    public AdminRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "角色列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:role:list')")
    public Result<List<RoleVO>> list() {
        return Result.success(roleService.listRoles());
    }

    @Operation(summary = "创建角色")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:role:create')")
    public Result<RoleVO> create(@Valid @RequestBody RoleCreateDTO dto) {
        return Result.success(roleService.createRole(dto));
    }

    @Operation(summary = "修改角色")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:role:update')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateDTO dto) {
        roleService.updateRole(id, dto);
        return Result.success();
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:role:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.success();
    }

    @Operation(summary = "查询角色已绑定的权限")
    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('admin:role:list')")
    public Result<List<PermissionVO>> listPermissions(@PathVariable Long id) {
        return Result.success(roleService.listRolePermissions(id));
    }

    @Operation(summary = "给角色分配权限")
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('admin:role:assign-permission')")
    public Result<Void> assignPermissions(@PathVariable Long id,
                                          @Valid @RequestBody AssignPermissionsDTO dto) {
        roleService.assignPermissions(id, dto.getPermissionIds());
        return Result.success();
    }
}
