package com.zest.mindforgeai.controller.internal;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.AiModelRuntimeVO;
import com.zest.mindforgeai.service.InternalAiModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口：把 AI 模型配置交给 Python 服务。
 *
 * <p>解决的是「管理端能配 AI 模型，但配了不生效」—— 真正调模型的是 Python，
 * 而它之前只读自己的 {@code .env}。
 *
 * <p><b>认证</b>：不走 JWT，由 {@code InternalTokenFilter} 校验 {@code X-Service-Token}。
 *
 * <p>⚠️⚠️ <b>这个响应体里带明文 API Key</b> —— 它是整个项目里唯一会返回明文 Key 的接口。
 * 所以：
 * <ul>
 *   <li>只能挂在 {@code /internal/**} 下（服务令牌保护，且上线要用 Nginx / 内网挡住）</li>
 *   <li>日志里绝不能打响应体（{@code InternalAiModelServiceImpl} 只记了「下发了哪几条」）</li>
 *   <li>别把它复制到任何 {@code /api/**} 下的接口上</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-10
 */
@Tag(name = "内部接口-AI 模型配置（仅 Python AI 服务）")
@RestController
@RequestMapping("/internal/ai-models")
public class InternalAiModelController {

    private final InternalAiModelService internalAiModelService;

    public InternalAiModelController(InternalAiModelService internalAiModelService) {
        this.internalAiModelService = internalAiModelService;
    }

    /**
     * 拉取所有启用的模型配置（Key 已解密）。
     *
     * <p>Python 侧的后台协程每 60 秒调一次，整体替换内存缓存 ——
     * 所以这里不做缓存、也不分页，配置总共就几条。
     */
    @Operation(summary = "拉取启用的 AI 模型配置（含明文 Key）")
    @GetMapping
    public Result<List<AiModelRuntimeVO>> list() {
        return Result.success(internalAiModelService.listEnabled());
    }
}
