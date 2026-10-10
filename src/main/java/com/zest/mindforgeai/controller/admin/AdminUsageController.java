package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.UsageByModelVO;
import com.zest.mindforgeai.pojo.vo.UsageDailyVO;
import com.zest.mindforgeai.pojo.vo.UsageSummaryVO;
import com.zest.mindforgeai.service.AdminUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端：AI 使用量统计。
 *
 * <p>数据来自 {@code ai_usage_record}，由 Python 服务每次调完模型后上报
 * （见 {@code POST /internal/ai-usage}）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Tag(name = "管理端-AI 使用量")
@RestController
@RequestMapping("/admin/usage")
public class AdminUsageController {

    private final AdminUsageService adminUsageService;

    public AdminUsageController(AdminUsageService adminUsageService) {
        this.adminUsageService = adminUsageService;
    }

    /**
     * 总量。
     *
     * <p>日期都可不传 —— 不传就是最近 7 天（含今天）。
     * 三个接口共用同一套日期参数，前端切时间范围时一次改三个请求即可。
     */
    @Operation(summary = "AI 使用量总览")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:usage:read')")
    public Result<UsageSummaryVO> summary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.success(adminUsageService.summary(from, to));
    }

    /** 按天汇总 —— 前端拿它画趋势图。只返回有记录的天。 */
    @Operation(summary = "按天的 AI 使用量")
    @GetMapping("/daily")
    @PreAuthorize("hasAuthority('admin:usage:read')")
    public Result<List<UsageDailyVO>> daily(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.success(adminUsageService.daily(from, to));
    }

    /** 按「场景 + 模型」汇总 —— 用来回答「钱花在哪个场景、哪个模型上」。 */
    @Operation(summary = "按模型的 AI 使用量")
    @GetMapping("/models")
    @PreAuthorize("hasAuthority('admin:usage:read')")
    public Result<List<UsageByModelVO>> byModel(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.success(adminUsageService.byModel(from, to));
    }
}
