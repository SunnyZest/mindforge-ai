package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.BatchIdsDTO;
import com.zest.mindforgeai.pojo.dto.DocumentStatusUpdateDTO;
import com.zest.mindforgeai.pojo.dto.ImportUrlDTO;
import com.zest.mindforgeai.pojo.vo.AdminDocumentVO;
import com.zest.mindforgeai.pojo.vo.ChunkVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AdminDocumentService;
import com.zest.mindforgeai.service.ChunkService;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端文档，路径对齐文档 §二十四。
 * <p>
 * 看的是<b>所有用户</b>的文档，和用户端的 {@code /app/documents} 不是一回事。
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
    private final AdminDocumentService adminDocumentService;

    public AdminDocumentController(ChunkService chunkService,
                                   AdminDocumentService adminDocumentService) {
        this.chunkService = chunkService;
        this.adminDocumentService = adminDocumentService;
    }

    @Operation(summary = "文档列表（所有用户）")
    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('admin:document:list')")
    public Result<PageResultVO<AdminDocumentVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(adminDocumentService.pageDocuments(
                page, pageSize, keyword, knowledgeBaseId, userId, status, sortBy, sortOrder));
    }

    @Operation(summary = "文档详情")
    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('admin:document:list')")
    public Result<AdminDocumentVO> detail(@PathVariable Long id) {
        return Result.success(adminDocumentService.getDetail(id));
    }

    @Operation(summary = "管理端上传文档到知识库")
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents/upload")
    @PreAuthorize("hasAuthority('admin:document:upload')")
    public Result<AdminDocumentVO> upload(@PathVariable Long knowledgeBaseId,
                                          @RequestPart("file") MultipartFile file,
                                          @RequestParam(required = false) String description) {
        return Result.success(adminDocumentService.upload(knowledgeBaseId, file, description));
    }

    @Operation(summary = "管理端通过网页 URL 导入文档")
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents/import-url")
    @PreAuthorize("hasAuthority('admin:document:upload')")
    public Result<AdminDocumentVO> importUrl(@PathVariable Long knowledgeBaseId,
                                             @Valid @RequestBody ImportUrlDTO dto) {
        return Result.success(adminDocumentService.importUrl(knowledgeBaseId, dto.getUrl()));
    }

    /**
     * 手工修改文档处理状态。
     * <p>
     * ⚠️ 权限码 {@code admin:document:update} 只给 SUPER_ADMIN，和下面的「重试」区分开：
     * 重试只会把状态重置为 PENDING（方向安全），而这个接口能直接把文档标成 SUCCESS ——
     * 那会让检索拿到不完整的内容，属于能破坏数据的操作。
     */
    @Operation(summary = "修改文档处理状态")
    @PutMapping("/documents/{id}/status")
    @PreAuthorize("hasAuthority('admin:document:update')")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody DocumentStatusUpdateDTO dto) {
        adminDocumentService.updateStatus(id, dto);
        return Result.success();
    }

    @Operation(summary = "重试文档")
    @PostMapping("/documents/{id}/retry")
    @PreAuthorize("hasAuthority('admin:document:retry')")
    public Result<Void> retry(@PathVariable Long id) {
        adminDocumentService.retry(id);
        return Result.success();
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('admin:document:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        adminDocumentService.delete(id);
        return Result.success();
    }

    @Operation(summary = "批量重试文档")
    @PostMapping("/documents/batch-retry")
    @PreAuthorize("hasAuthority('admin:document:retry')")
    public Result<Integer> batchRetry(@Valid @RequestBody BatchIdsDTO dto) {
        return Result.success(adminDocumentService.batchRetry(dto.getIds()));
    }

    @Operation(summary = "批量删除文档")
    @DeleteMapping("/documents/batch")
    @PreAuthorize("hasAuthority('admin:document:delete')")
    public Result<Integer> batchDelete(@Valid @RequestBody BatchIdsDTO dto) {
        return Result.success(adminDocumentService.batchDelete(dto.getIds()));
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
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        return Result.success(chunkService.pageByDocument(id, page, pageSize, sortBy, sortOrder));
    }
}
