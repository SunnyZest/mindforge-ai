package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.PermissionVO;
import com.zest.mindforgeai.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端权限查询，对外路径 {@code /api/admin/permissions}（文档 §十四）。
 * <p>
 * 权限是系统内置字典，<b>只读</b> —— 不提供增删改接口。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Tag(name = "管理端-权限管理")
@RestController
@RequestMapping("/admin/permissions")
public class AdminPermissionController {

    private final PermissionService permissionService;

    public AdminPermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Operation(summary = "获取全部权限")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:permission:list')")
    public Result<List<PermissionVO>> listAll() {
        return Result.success(permissionService.listAll());
    }
}
