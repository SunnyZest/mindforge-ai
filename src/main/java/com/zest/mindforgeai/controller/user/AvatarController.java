package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 头像读取 —— <b>唯一一个公开的用户端接口</b>。
 *
 * <p><b>为什么必须公开</b>：前端是 {@code <img src="/api/app/avatars/12">}，
 * 而 img 标签<b>带不上</b> Authorization 头。想让头像能显示，就只有两条路 ——
 * 要么公开读，要么前端用 fetch 拿 blob 再赋给 src（那样没有浏览器缓存，得不偿失）。
 *
 * <p><b>公开读的安全边界就在这个类里</b>：
 * <ul>
 *   <li>只允许 {@code biz_type = avatars} 的文件 —— 否则别人拿这个接口
 *       把简历、文档也一并拉走（{@code sys_file.id} 是可枚举的）</li>
 *   <li>「不存在 / 已删除 / 不是头像」统一 404 —— 不区分等于告诉对方「这个 id 存在」，
 *       那就成了探测文件 id 的工具</li>
 *   <li>不直接吐字节，而是 302 跳到 OSS 预签名 URL —— 文件流量不过后端，
 *       和文档下载（{@code DocumentController.download}）保持一致</li>
 * </ul>
 *
 * <p>⚠️ 改这个类之前先想清楚：它没有任何鉴权兜底。
 *
 * @author crx
 * @since 2026-10-10
 */
@Tag(name = "用户端-头像")
@RestController
@RequestMapping("/app/avatars")
public class AvatarController {

    private final FileService fileService;

    public AvatarController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 取头像（302 跳预签名 URL）。
     *
     * @param fileId sys_file 的主键
     */
    @Operation(summary = "获取头像（302 跳转预签名 URL）")
    @GetMapping("/{fileId}")
    public ResponseEntity<Void> avatar(@PathVariable Long fileId) {
        // ★ 业务类型校验在这里，不在调用方 —— 这是公开接口，没有第二道防线
        SysFile file = fileService.getByBiz(fileId, FileService.BIZ_AVATAR);
        if (file == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        String url = fileService.downloadUrl(file);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .build();
    }
}
