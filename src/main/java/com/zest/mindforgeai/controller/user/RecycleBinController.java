package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.RecycleBinVO;
import com.zest.mindforgeai.pojo.vo.RecycleRestoreResultVO;
import com.zest.mindforgeai.service.RecycleBinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端回收站（最近删除）。
 *
 * <p>⚠️ <b>恢复不是「撤销操作」，只是「把逻辑删除改回去」</b>：
 * 它不还原删除之后的任何变化，也不处理关联数据 —— 一期这五种资源删除时
 * 本来就没有物理清理，所以改回 {@code deleted = 0} 就完整了。
 * 简历 / 文档 / 知识库要复杂得多（技能被物理删、向量被清），留到后面几期。
 *
 * @author crx
 * @since 2026-10-22
 */
@Slf4j
@Tag(name = "用户端-回收站")
@RestController
@RequestMapping("/app/recycle-bin")
public class RecycleBinController {

    private final RecycleBinService recycleBinService;

    public RecycleBinController(RecycleBinService recycleBinService) {
        this.recycleBinService = recycleBinService;
    }

    @Operation(summary = "最近删除列表", description = "只返回本人、且还在恢复期内的记录")
    @GetMapping
    @PreAuthorize("hasAuthority('app:recycle:read')")
    public Result<PageResultVO<RecycleBinVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String resourceType) {

        return Result.success(recycleBinService.page(page, pageSize, resourceType));
    }

    @Operation(summary = "恢复",
            description = "把原记录的逻辑删除改回未删除，并从回收站移除。"
                    + "返回 complete=false 表示关联数据（技能/题目/消息）没保存下来，只恢复了主记录")
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('app:recycle:write')")
    public Result<RecycleRestoreResultVO> restore(@PathVariable Long id) {
        return Result.success(recycleBinService.restore(id));
    }

    @Operation(summary = "彻底删除", description = "不可恢复；只删处于逻辑删除状态的原记录，已被恢复的不受影响")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:recycle:write')")
    public Result<Void> purge(@PathVariable Long id) {
        recycleBinService.purge(id);
        return Result.success();
    }
}
