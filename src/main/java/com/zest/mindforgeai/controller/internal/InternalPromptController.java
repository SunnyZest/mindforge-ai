package com.zest.mindforgeai.controller.internal;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.PromptRuntimeVO;
import com.zest.mindforgeai.service.InternalPromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口：把提示词下发给 Python 服务。
 *
 * <p><b>认证</b>：不走 JWT，由 {@code InternalTokenFilter} 校验 {@code X-Service-Token}。
 *
 * <p>Python 侧的后台协程每 60 秒拉一次、整体替换内存缓存 ——
 * 所以这里不做缓存、不分页（提示词总共就十几条）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Tag(name = "内部接口-提示词（仅 Python AI 服务）")
@RestController
@RequestMapping("/internal/prompts")
public class InternalPromptController {

    private final InternalPromptService internalPromptService;

    public InternalPromptController(InternalPromptService internalPromptService) {
        this.internalPromptService = internalPromptService;
    }

    /** 拉取所有已发布的提示词（含正文）。 */
    @Operation(summary = "拉取已发布的提示词")
    @GetMapping
    public Result<List<PromptRuntimeVO>> list() {
        return Result.success(internalPromptService.listPublished());
    }
}
