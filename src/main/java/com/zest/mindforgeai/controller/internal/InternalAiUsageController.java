package com.zest.mindforgeai.controller.internal;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.AiUsageReportDTO;
import com.zest.mindforgeai.service.InternalAiUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口：接收 Python 上报的 AI 使用量。
 *
 * <p><b>认证</b>：不走 JWT，由 {@code InternalTokenFilter} 校验 {@code X-Service-Token}。
 *
 * <p>⚠️ <b>永远返回成功</b>。这是刻意的 —— 对 Python 来说上报是 fire-and-forget，
 * 落库失败没有它能做的事（重试/落队列都在 Java 侧才合理，而现在没做）。
 * 返回失败只会让 Python 的日志多一堆噪音，业务日志被淹。
 * 落库失败由 {@code InternalAiUsageServiceImpl} 记 WARNING。
 *
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Tag(name = "内部接口-AI 使用量（仅 Python AI 服务）")
@RestController
@RequestMapping("/internal/ai-usage")
public class InternalAiUsageController {

    private final InternalAiUsageService internalAiUsageService;

    public InternalAiUsageController(InternalAiUsageService internalAiUsageService) {
        this.internalAiUsageService = internalAiUsageService;
    }

    /**
     * 上报一次模型调用的用量。
     *
     * <p>一次调用一条 —— 不做批量。批量要引入缓冲、定时 flush、退出时 flush 这一套，
     * 而单条上报只是一次本地 HTTP 往返（几毫秒），且 Python 侧不 await。
     */
    @Operation(summary = "上报 AI 使用量")
    @PostMapping
    public Result<Void> report(@Valid @RequestBody AiUsageReportDTO dto) {
        internalAiUsageService.record(dto);
        return Result.success();
    }
}
