package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.ChunkVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.ChunkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端文档。
 *
 * @author crx
 * @since 2026-10-07
 */
@Slf4j
@Tag(name = "管理端-文档")
@RestController
@RequestMapping("/admin")
public class AdminDocumentController {

    private final ChunkService chunkService;

    public AdminDocumentController(ChunkService chunkService) {
        this.chunkService = chunkService;
    }

    /**
     * 看某个文档的分块。
     * <p>
     * 管理员排查「这份文档为什么检索不到」时的第一站 ——
     * 分块切得不对（比如整篇挤成一块、或者切碎了）会直接影响检索效果。
     * <p>
     * 复用 {@code admin:document:list} 权限，不单独加码 ——
     * 看分块本来就是「看文档」的一部分。
     */
    @Operation(summary = "查看文档的分块列表")
    @GetMapping("/documents/{id}/chunks")
    @PreAuthorize("hasAuthority('admin:document:list')")
    public Result<PageResultVO<ChunkVO>> pageChunks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(chunkService.pageByDocument(id, page, pageSize));
    }
}
