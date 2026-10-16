package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.AiStatusVO;
import com.zest.mindforgeai.service.AppStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端应用状态（首页状态徽章）。
 * <p>
 * ⚠️ 这个接口<b>不挂 {@code @PreAuthorize}</b>：它只回答「AI 能不能用」，
 * 不属于任何业务模块，任何登录用户都该能看到（与 {@code /app/auth/me} 同一档）。
 *
 * @author crx
 * @since 2026-10-16
 */
@Tag(name = "用户端-应用状态")
@RestController
@RequestMapping("/app/ai")
public class AppStatusController {

    private final AppStatusService appStatusService;

    public AppStatusController(AppStatusService appStatusService) {
        this.appStatusService = appStatusService;
    }

    @Operation(summary = "AI 能力状态（首页徽章：就绪 / 配置不完整 / 离线）")
    @GetMapping("/status")
    public Result<AiStatusVO> aiStatus() {
        return Result.success(appStatusService.aiStatus());
    }
}
