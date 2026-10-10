package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.AuditLogVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 管理端审计日志查询，对外路径 {@code /api/admin/audit-logs}（文档 §二十八）。
 * <p>
 * <b>只提供查询，不提供删除</b> —— 能删的审计日志等于没有审计。
 * 日志清理应通过 DBA 的归档策略处理，而不是开一个接口。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Tag(name = "管理端-审计日志")
@RestController
@RequestMapping("/admin/audit-logs")
public class AdminAuditLogController {

    private final AuditService auditService;

    public AdminAuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Operation(summary = "审计日志列表（分页 + 多条件筛选）")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:audit:list')")
    public Result<PageResultVO<AuditLogVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(auditService.pageLogs(
                page, pageSize, userId, action, resourceType, result, from, to, sortBy, sortOrder));
    }
}
