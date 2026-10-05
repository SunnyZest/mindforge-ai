package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.DocumentVO;
import com.zest.mindforgeai.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;

/**
 * 用户端文档，路径对齐文档 §十六。
 * <p>
 * 上传走 multipart/form-data；下载走 302 跳转到<b>预签名 URL</b> ——
 * 文件流量直接从对象存储到客户端，不经过后端。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Tag(name = "用户端-文档")
@RestController
@RequestMapping("/app")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(summary = "上传文档到知识库")
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    @PreAuthorize("hasAuthority('app:document:upload')")
    public Result<DocumentVO> upload(@PathVariable Long knowledgeBaseId,
                                     @RequestPart("file") MultipartFile file,
                                     @RequestParam(required = false) String description) {
        return Result.success(documentService.upload(knowledgeBaseId, file, description));
    }

    /**
     * 下载文档：302 跳到预签名 URL。
     * <p>
     * 用 302 而不是「后端读文件再吐出去」—— 后者会让所有文件流量压在后端，
     * 大文件还会占满 Tomcat 线程。
     */
    @Operation(summary = "下载文档（302 跳转预签名 URL）")
    @GetMapping("/documents/{id}/download")
    @PreAuthorize("hasAuthority('app:document:read')")
    public ResponseEntity<Void> download(@PathVariable Long id) {
        String url = documentService.downloadUrl(id);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .build();
    }

    /**
     * 删除文档：只做逻辑删除，<b>OSS 对象保留恢复窗口</b>（默认 30 天），
     * 到期由后台清理任务判断引用计数后物理删除。
     */
    @Operation(summary = "删除文档")
    @DeleteMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('app:document:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.success();
    }
}
