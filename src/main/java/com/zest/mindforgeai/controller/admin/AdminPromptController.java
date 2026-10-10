package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.PromptCreateDTO;
import com.zest.mindforgeai.pojo.dto.PromptUpdateDTO;
import com.zest.mindforgeai.pojo.vo.PromptGroupVO;
import com.zest.mindforgeai.pojo.vo.PromptVO;
import com.zest.mindforgeai.service.AdminPromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 管理端：提示词管理。
 *
 * <p>⚠️ 改提示词会<b>立刻影响所有用户的 AI 输出</b>，是典型的高危操作 ——
 * 所以五个写操作全部记审计（见 {@code AdminPromptServiceImpl}）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Tag(name = "管理端-Prompt 管理")
@RestController
@RequestMapping("/admin/prompts")
public class AdminPromptController {

    private final AdminPromptService adminPromptService;

    public AdminPromptController(AdminPromptService adminPromptService) {
        this.adminPromptService = adminPromptService;
    }

    /** 按变体分组列出，每个变体带它的全部历史版本（版本号倒序）。 */
    @Operation(summary = "提示词列表（按变体分组，含版本历史）")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:prompt:manage')")
    public Result<List<PromptGroupVO>> list(
            @RequestParam(required = false) String scene) {
        return Result.success(adminPromptService.listGroups(scene));
    }

    /**
     * 新建一个版本。
     *
     * <p>⚠️ 同一个 (scene, name) 再调一次是给这个变体<b>加一个新版本</b>，
     * 不是新建变体 —— 想加变体请换一个 name。
     */
    @Operation(summary = "新建提示词版本（同变体自动递增版本号）")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:prompt:manage')")
    public Result<PromptVO> create(@Valid @RequestBody PromptCreateDTO dto) {
        return Result.success(adminPromptService.create(dto));
    }

    /** 改草稿。已发布的会返回 40902 —— 要改就新建一个版本。 */
    @Operation(summary = "修改提示词草稿（已发布的拒绝）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:prompt:manage')")
    public Result<PromptVO> update(@PathVariable Long id,
                                   @Valid @RequestBody PromptUpdateDTO dto) {
        return Result.success(adminPromptService.update(id, dto));
    }

    /** 发布：这一版生效，同一变体下原来那版归档。 */
    @Operation(summary = "发布提示词")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('admin:prompt:manage')")
    public Result<PromptVO> publish(@PathVariable Long id) {
        return Result.success(adminPromptService.publish(id));
    }

    /** 回滚：把某个已归档的版本重新设为生效版本。 */
    @Operation(summary = "回滚到某个已归档版本")
    @PostMapping("/{id}/rollback")
    @PreAuthorize("hasAuthority('admin:prompt:manage')")
    public Result<PromptVO> rollback(@PathVariable Long id) {
        return Result.success(adminPromptService.rollback(id));
    }

    /** 删除草稿或已归档版本。正在生效的不能删 —— 先回滚到别的版本。 */
    @Operation(summary = "删除提示词版本（正在生效的拒绝）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:prompt:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        adminPromptService.delete(id);
        return Result.success();
    }
}
