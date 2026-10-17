package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.SalaryEstimateDTO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.SalaryEstimateVO;
import com.zest.mindforgeai.service.SalaryEstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 薪酬估算，对外路径 {@code /api/app/salary/**}（ROADMAP 二-5 的另一半）。
 * <p>
 * ★ 这里给的是 <b>AI 估算的参考区间</b>，不是市场行情：
 * 我们没有真实薪酬样本，所以接口不提供「样本量 / 数据来源」这类字段，
 * 响应里必带 {@code caveat}（免责声明）与 {@code confidence}（置信度）。
 * <p>
 * ⚠️ 估算要调模型（十几秒到一分钟），与「Offer 对比器」那种纯本地计算不同。
 *
 * @author crx
 * @since 2026-10-17
 */
@Slf4j
@Tag(name = "用户端-薪酬估算")
@RestController
@RequestMapping("/app/salary")
public class SalaryController {

    private final SalaryEstimateService salaryEstimateService;

    public SalaryController(SalaryEstimateService salaryEstimateService) {
        this.salaryEstimateService = salaryEstimateService;
    }

    @Operation(summary = "发起薪酬估算（AI 参考区间）",
            description = "按城市 / 岗位 / 年限估算月薪与年包的 P50 / P75 / P90；"
                    + "同参数 7 天内直接返回上次结果（cached=true）")
    @PostMapping("/estimate")
    @PreAuthorize("hasAuthority('app:salary:write')")
    public Result<SalaryEstimateVO> estimate(@Valid @RequestBody SalaryEstimateDTO dto) {
        return Result.success(salaryEstimateService.estimate(dto));
    }

    @Operation(summary = "估算历史（分页）")
    @GetMapping("/estimates")
    @PreAuthorize("hasAuthority('app:salary:read')")
    public Result<PageResultVO<SalaryEstimateVO>> history(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.success(salaryEstimateService.history(page, pageSize));
    }
}
