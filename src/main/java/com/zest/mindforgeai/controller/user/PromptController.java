package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.PromptOptionVO;
import com.zest.mindforgeai.service.PromptQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端：可选的回答风格。
 *
 * <p>给前端下拉用。返回空列表时前端就别显示切换入口 —— 说明管理员还没配可选项。
 *
 * <p>⚠️ 只回 id / label / description，<b>不回提示词正文</b>。
 *
 * @author crx
 * @since 2026-10-10
 */
@Tag(name = "用户端-回答风格")
@RestController
@RequestMapping("/app/prompts")
public class PromptController {

    private final PromptQueryService promptQueryService;

    public PromptController(PromptQueryService promptQueryService) {
        this.promptQueryService = promptQueryService;
    }

    /**
     * 列出某个场景下可选的回答风格。
     *
     * @param scene 场景，如 {@code RAG_CHAT}。大小写不敏感
     */
    @Operation(summary = "列出可选的回答风格")
    @GetMapping
    @PreAuthorize("hasAuthority('app:prompt:read')")
    public Result<List<PromptOptionVO>> list(@RequestParam String scene) {
        return Result.success(promptQueryService.listSelectable(scene));
    }
}
