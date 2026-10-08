package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.DashboardVO;
import com.zest.mindforgeai.service.AdminDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台首页，路径对齐文档 §二十三。
 * <p>
 * 只读接口，不写审计 —— 审计留给「改变数据」的操作，
 * 否则管理员每刷新一次首页就多一条记录，真正需要追查的条目会被淹没。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "管理端-首页统计")
@RestController
@RequestMapping("/admin")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @Operation(summary = "首页统计")
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('admin:dashboard:read')")
    public Result<DashboardVO> dashboard() {
        return Result.success(adminDashboardService.getDashboard());
    }
}
