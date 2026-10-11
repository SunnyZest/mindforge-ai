package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.AdminUserCreateDTO;
import com.zest.mindforgeai.pojo.dto.AssignRolesDTO;
import com.zest.mindforgeai.pojo.dto.UserStatusDTO;
import com.zest.mindforgeai.pojo.dto.UserUpdateDTO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.UserInfoVO;
import com.zest.mindforgeai.pojo.vo.UserSummaryVO;
import com.zest.mindforgeai.service.AdminUserService;
import com.zest.mindforgeai.service.SecondaryConfirmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户管理，对外路径 {@code /api/admin/users/**}（文档 §十三）。
 * <p>
 * 两层防护：
 * <ol>
 *   <li>{@code SecurityConfig} 的 URL 规则 —— {@code /admin/**} 要求 ADMIN / SUPER_ADMIN 角色</li>
 *   <li>本类的 {@code @PreAuthorize} —— 再按<b>权限码</b>细分到具体操作</li>
 * </ol>
 * 所以普通用户访问这里会被第 1 层拦成 403；ADMIN 若缺少某个权限码，会被第 2 层拦成 403。
 *
 * @author crx
 * @since 2026-10-04
 */
@Slf4j
@Tag(name = "管理端-用户管理")
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final SecondaryConfirmService secondaryConfirmService;

    public AdminUserController(AdminUserService adminUserService,
                              SecondaryConfirmService secondaryConfirmService) {
        this.adminUserService = adminUserService;
        this.secondaryConfirmService = secondaryConfirmService;
    }

    @Operation(summary = "用户列表（分页）")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:user:list')")
    public Result<PageResultVO<UserSummaryVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(adminUserService.pageUsers(
                page, pageSize, keyword, status, role, sortBy, sortOrder));
    }

    @Operation(summary = "创建用户")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:user:create')")
    public Result<UserInfoVO> create(@Valid @RequestBody AdminUserCreateDTO dto) {
        return Result.success(adminUserService.createUser(dto));
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:user:delete')")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        secondaryConfirmService.validateAndConsume(request);
        adminUserService.deleteUser(id);
        return Result.success();
    }

    @Operation(summary = "用户详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:user:detail')")
    public Result<UserInfoVO> detail(@PathVariable Long id) {
        return Result.success(adminUserService.getUserDetail(id));
    }

    @Operation(summary = "修改用户资料")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:user:update')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO dto) {
        adminUserService.updateProfile(id, dto);
        return Result.success();
    }

    @Operation(summary = "启用 / 禁用用户")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('admin:user:disable')")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDTO dto) {
        adminUserService.updateStatus(id, dto.getStatus());
        return Result.success();
    }

    @Operation(summary = "给用户分配角色")
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('admin:user:assign-role')")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesDTO dto) {
        adminUserService.assignRoles(id, dto.getRoleIds());
        return Result.success();
    }
}
