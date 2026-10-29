package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.AiModelCreateDTO;
import com.zest.mindforgeai.pojo.dto.AiModelUpdateDTO;
import com.zest.mindforgeai.pojo.vo.AiEffectiveConfigVO;
import com.zest.mindforgeai.pojo.vo.AiModelVO;
import com.zest.mindforgeai.service.AdminAiModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 AI 模型配置，路径对齐文档 §二十五。
 * <p>
 * ⚠️ 四个接口统一用 {@code admin:ai:manage}（只给 SUPER_ADMIN）。
 * 文档只定义了这一个权限码；如果将来需要「只读的管理员」，
 * 再单独拆一个 {@code admin:ai:read} 给列表接口即可。
 * <p>
 * <b>API Key 的处理</b>：入参可以带明文（只在这一次请求里存在，落库前加密），
 * 出参<b>永远不含明文</b>，只有 {@code apiKeyHint}（形如 ****abcd）。
 * 日志和审计同理。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "管理端-AI 配置")
@RestController
@RequestMapping("/admin/ai/models")
public class AdminAiModelController {

    private final AdminAiModelService adminAiModelService;

    public AdminAiModelController(AdminAiModelService adminAiModelService) {
        this.adminAiModelService = adminAiModelService;
    }

    @Operation(summary = "AI 模型配置列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:ai:manage')")
    public Result<List<AiModelVO>> list(@RequestParam(required = false) String scene) {
        return Result.success(adminAiModelService.listModels(scene));
    }

    /**
     * 当前<b>真正生效</b>的配置 —— 列表接口答不出的那个问题。
     * <p>
     * ★ 列表只说明「配了哪些」，说明不了「在用哪个」：
     * 某个用途有多条但都没勾默认时，生效的是 id 最大的那条；
     * 一条都没配时走 .env，库里根本没有这条记录。两种情况下
     * {@link #list} 都看不出来，排查只能去翻 {@code ai_usage_record.model_name}。
     * <p>
     * ⚠️ <b>路径不会和现有接口冲突</b>：本控制器没有 {@code GET /{id}}，
     * 所以 {@code /effective} 不会被当成 id 吃掉。
     * <p>
     * ⚠️ Python 不可达时返回 50201（不是空列表）—— 「查不到」和「没有生效配置」
     * 必须能区分，否则管理员会去配一个本来就有的模型。
     */
    @Operation(summary = "当前生效的 AI 配置（转发 Python 内存快照）")
    @GetMapping("/effective")
    @PreAuthorize("hasAuthority('admin:ai:manage')")
    public Result<AiEffectiveConfigVO> effective() {
        return Result.success(adminAiModelService.effectiveConfig());
    }

    @Operation(summary = "新增 AI 模型配置")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:ai:manage')")
    public Result<AiModelVO> create(@Valid @RequestBody AiModelCreateDTO dto) {
        return Result.success(adminAiModelService.create(dto));
    }

    @Operation(summary = "修改 AI 模型配置（apiKey 传 null 表示不修改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:ai:manage')")
    public Result<AiModelVO> update(@PathVariable Long id,
                                    @Valid @RequestBody AiModelUpdateDTO dto) {
        return Result.success(adminAiModelService.update(id, dto));
    }

    @Operation(summary = "删除 AI 模型配置")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:ai:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        adminAiModelService.delete(id);
        return Result.success();
    }
}
