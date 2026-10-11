package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.BatchIdsDTO;
import com.zest.mindforgeai.pojo.vo.AlertVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AlertService;
import com.zest.mindforgeai.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端高危操作告警。
 * <p>
 * 告警<b>不删除</b>（和审计一样，能删的告警等于没有）——
 * 已读就是终点；表太大时走 DBA 归档，不开接口。
 *
 * @author crx
 * @since 2026-10-11
 */
@Slf4j
@Tag(name = "管理端-高危操作告警")
@RestController
@RequestMapping("/admin/alerts")
public class AdminAlertController {

    private final AlertService alertService;

    public AdminAlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @Operation(summary = "告警列表（分页，可只看未读）")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:alert:list')")
    public Result<PageResultVO<AlertVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(alertService.pageAlerts(page, pageSize, unreadOnly, sortBy, sortOrder));
    }

    @Operation(summary = "未读数量（角标用）")
    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('admin:alert:list')")
    public Result<Long> unreadCount() {
        return Result.success(alertService.unreadCount());
    }

    @Operation(summary = "标记单条已读（幂等）")
    @PutMapping("/{id}/read")
    @PreAuthorize("hasAuthority('admin:alert:list')")
    public Result<Void> markRead(@PathVariable long id) {
        alertService.markRead(id, SecurityUtils.getUserId());
        return Result.success();
    }

    @Operation(summary = "全部标记已读")
    @PutMapping("/read-all")
    @PreAuthorize("hasAuthority('admin:alert:list')")
    public Result<Integer> markAllRead() {
        return Result.success(alertService.markAllRead(SecurityUtils.getUserId()));
    }

    @Operation(summary = "批量标记已读")
    @PutMapping("/batch-read")
    @PreAuthorize("hasAuthority('admin:alert:list')")
    public Result<Integer> batchMarkRead(@Valid @RequestBody BatchIdsDTO dto) {
        return Result.success(alertService.batchMarkRead(dto.getIds(), SecurityUtils.getUserId()));
    }
}
