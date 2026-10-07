package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.ChunkVO;
import com.zest.mindforgeai.service.ChunkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端文档分块。
 * <p>
 * 只提供一个「按 id 查单个分块」的接口，用途是<b>对话引用溯源</b> ——
 * 用户在回答里点某条引用，前端拿 chunkId 换回那段原文。
 * <p>
 * 刻意<b>不提供</b>「列出某文档的全部分块」：分块是内部实现细节，
 * 用户不关心文档被切成几块，列表也不好展示（一个文档可能几百块），
 * 而且 overlap 造成的重复会让用户误以为系统有问题。
 * 要看分块列表请走管理端。
 *
 * @author crx
 * @since 2026-10-07
 */
@Slf4j
@Tag(name = "用户端-文档分块")
@RestController
@RequestMapping("/app")
public class ChunkController {

    private final ChunkService chunkService;

    public ChunkController(ChunkService chunkService) {
        this.chunkService = chunkService;
    }

    @Operation(summary = "按 id 查分块（对话引用溯源用）")
    @GetMapping("/chunks/{id}")
    @PreAuthorize("hasAuthority('app:document:read')")
    public Result<ChunkVO> getChunk(@PathVariable Long id) {
        return Result.success(chunkService.getForCurrentUser(id));
    }
}
