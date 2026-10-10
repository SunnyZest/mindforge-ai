package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.KnowledgeBaseCreateDTO;
import com.zest.mindforgeai.pojo.dto.KnowledgeBaseUpdateDTO;
import com.zest.mindforgeai.pojo.vo.KnowledgeBaseStatsVO;
import com.zest.mindforgeai.pojo.vo.KnowledgeBaseVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.KnowledgeBaseService;
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

/**
 * 用户端知识库，对外路径 {@code /api/app/knowledge-bases/**}（文档 §十五）。
 * <p>
 * 两层防护：{@code /app/**} 要求登录，本类的 {@code @PreAuthorize} 再按权限码细分。
 * <p>
 * <b>归属校验在 Service 层做</b>（{@code knowledge_base.user_id == 当前用户}）——
 * 这是文档 §十五 的硬性要求，不能只靠前端传的 id。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Tag(name = "用户端-知识库")
@RestController
@RequestMapping("/app/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Operation(summary = "创建知识库")
    @PostMapping
    @PreAuthorize("hasAuthority('app:knowledge:write')")
    public Result<KnowledgeBaseVO> create(@Valid @RequestBody KnowledgeBaseCreateDTO dto) {
        return Result.success(knowledgeBaseService.create(dto));
    }

    @Operation(summary = "知识库列表（分页）")
    @GetMapping
    @PreAuthorize("hasAuthority('app:knowledge:read')")
    public Result<PageResultVO<KnowledgeBaseVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(
                knowledgeBaseService.pageKnowledgeBases(page, pageSize, keyword, status, sortBy, sortOrder));
    }

    @Operation(summary = "知识库详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('app:knowledge:read')")
    public Result<KnowledgeBaseVO> detail(@PathVariable Long id) {
        return Result.success(knowledgeBaseService.getDetail(id));
    }

    @Operation(summary = "修改知识库")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('app:knowledge:write')")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody KnowledgeBaseUpdateDTO dto) {
        knowledgeBaseService.update(id, dto);
        return Result.success();
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:knowledge:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.success();
    }

    @Operation(summary = "知识库统计，文档数 / 分块数 / 完成数 / 失败数")
    @GetMapping("/{id}/stats")
    @PreAuthorize("hasAuthority('app:knowledge:read')")
    public Result<KnowledgeBaseStatsVO> stats(@PathVariable Long id) {
        return Result.success(knowledgeBaseService.getStats(id));
    }
}
