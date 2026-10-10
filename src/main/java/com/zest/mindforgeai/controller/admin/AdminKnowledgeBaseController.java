package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.AdminKnowledgeBaseVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AdminKnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端知识库管理，路径对齐文档 §二十四。
 * <p>
 * 两层防护（同其他管理端接口）：
 * <ol>
 *   <li>{@code SecurityConfig} 的 URL 规则 —— {@code /admin/**} 要求 ADMIN / SUPER_ADMIN 角色</li>
 *   <li>本类的 {@code @PreAuthorize} —— 再按权限码细分</li>
 * </ol>
 * <p>
 * ⚠️ 这里看的是<b>所有用户</b>的知识库，和用户端的 {@code /app/knowledge-bases}
 * 不是一回事。删除走的是同一套级联规则（连带清理文档和对话），
 * 且比用户端多一个 {@code byAdmin} 审计标记 —— 事后追查时能分清是谁删的。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "管理端-知识库管理")
@RestController
@RequestMapping("/admin/knowledge-bases")
public class AdminKnowledgeBaseController {

    private final AdminKnowledgeService adminKnowledgeService;

    public AdminKnowledgeBaseController(AdminKnowledgeService adminKnowledgeService) {
        this.adminKnowledgeService = adminKnowledgeService;
    }

    @Operation(summary = "知识库列表（所有用户）")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:knowledge:list')")
    public Result<PageResultVO<AdminKnowledgeBaseVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(adminKnowledgeService.pageKnowledgeBases(
                page, pageSize, keyword, status, userId, sortBy, sortOrder));
    }

    @Operation(summary = "知识库详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:knowledge:list')")
    public Result<AdminKnowledgeBaseVO> detail(@PathVariable Long id) {
        return Result.success(adminKnowledgeService.getDetail(id));
    }

    /**
     * 删除知识库。
     * <p>
     * 用单独的高危权限码（只给 SUPER_ADMIN），不复用 {@code admin:knowledge:list} ——
     * 「能看」和「能删别人的数据」不该是同一个权限。
     */
    @Operation(summary = "删除知识库（级联删除其下文档与对话）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:knowledge:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        adminKnowledgeService.delete(id);
        return Result.success();
    }
}
