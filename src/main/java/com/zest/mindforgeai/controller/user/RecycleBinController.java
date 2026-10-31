package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.RecycleBinVO;
import com.zest.mindforgeai.pojo.vo.RecycleRestoreResultVO;
import com.zest.mindforgeai.service.RecycleBinService;
import com.zest.mindforgeai.service.SecondaryConfirmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final SecondaryConfirmService secondaryConfirmService;

    public RecycleBinController(RecycleBinService recycleBinService,
                                SecondaryConfirmService secondaryConfirmService) {
        this.recycleBinService = recycleBinService;
        this.secondaryConfirmService = secondaryConfirmService;
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

    /**
     * 彻底删除 —— <b>不可恢复，必须二次确认</b>。
     * <p>
     * 这是用户端唯一一个「删了真没了」的操作：恢复只是把 {@code deleted} 改回去，
     * 而这个是把行物理删掉。所以要求请求头带二次确认凭据：
     * <ul>
     *   <li>{@code X-Confirm-Token}：先调 {@code POST /app/auth/confirm-token}（验密码换一次性凭据），
     *       再把凭据放进来。<b>推荐这种方式</b> —— 凭据一次性，密码不会跟着每个请求跑</li>
     *   <li>{@code X-Confirm-Password}：直接把当前密码放请求头，每次都要传</li>
     * </ul>
     * <p>
     * ⚠️ <b>为什么前端弹个「确定要删吗」的窗口不算数</b>：
     * 那个弹窗只存在于正常用户的浏览器里，攻击者拿到 token 后写个脚本直接调接口就绕过了。
     * 真正的防护在后端 —— 必须能证明「操作的人知道密码」。
     */
    @Operation(summary = "彻底删除",
            description = "不可恢复；需要二次确认（X-Confirm-Token 或 X-Confirm-Password 请求头）。"
                    + "缺凭据返回 40304，凭据错误或过期返回 40305")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:recycle:write')")
    public Result<Void> purge(@PathVariable Long id, HttpServletRequest request) {
        // 放在最前面：凭据不过就不该碰数据库
        secondaryConfirmService.validateAndConsume(request);
        recycleBinService.purge(id);
        log.info("彻底删除回收站记录 id={}", id);
        return Result.success();
    }
}
