package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.UserProfileUpdateDTO;
import com.zest.mindforgeai.pojo.vo.UserProfileVO;
import com.zest.mindforgeai.pojo.vo.UserPublicVO;
import com.zest.mindforgeai.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户端个人资料，路径对齐文档 §十二。
 * <p>
 * ⚠️ 本类<b>没有 {@code @PreAuthorize}</b>，是有意的：
 * 其他 app 接口都带权限码，是因为它们操作的是<b>资源</b>（哪个知识库、哪个文档），
 * 权限码决定「能碰哪一类资源」。
 * 而这里操作的永远是<b>当前登录用户自己</b> —— 目标 id 从 SecurityContext 取，
 * 不从入参取，所以加权限码并不能多挡住什么：
 * 只要 {@code /app/**} 的「必须登录」生效就够了（{@code SecurityConfig} 里配的）。
 * <p>
 * 反过来说，如果这里挂一个 {@code app:user:update} 之类的权限码，
 * 就得给 USER 角色也授权 —— 那等于「所有登录用户都能改自己的资料」，
 * 和现在的结果完全一样，只是多绕了一层。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "用户端-个人资料")
@RestController
@RequestMapping("/app")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @Operation(summary = "获取个人资料")
    @GetMapping("/user/profile")
    public Result<UserProfileVO> profile() {
        return Result.success(userProfileService.getProfile());
    }

    @Operation(summary = "修改个人资料（昵称 / 头像）")
    @PutMapping("/user/profile")
    public Result<UserProfileVO> updateProfile(@Valid @RequestBody UserProfileUpdateDTO dto) {
        return Result.success(userProfileService.updateProfile(dto));
    }

    /**
     * 上传头像。
     * <p>
     * 和 {@code PUT /user/profile} 的分工：那个收的是「头像 URL 字符串」，
     * 是给「用外部图片地址」留的口子；真正传文件走这里。
     * <p>
     * ⚠️ 本接口<b>没有权限码</b>，理由和本类其他接口一样：
     * 它操作的永远是「当前登录用户自己」，目标 id 从 SecurityContext 取，不从入参取。
     *
     * @param file 图片文件（jpg / jpeg / png / webp / gif，≤2MB）
     */
    @Operation(summary = "上传头像")
    @PostMapping("/user/avatar")
    public Result<UserProfileVO> uploadAvatar(@RequestPart("file") MultipartFile file) {
        return Result.success(userProfileService.uploadAvatar(file));
    }

    /**
     * 用户公开信息。
     * <p>
     * 只返回 id / 用户名 / 昵称 / 头像 —— 邮箱、手机号、状态这些不给。
     * 因为路径里的 id 是别人可以随便猜的，返回什么就等于泄漏什么。
     */
    @Operation(summary = "用户公开信息")
    @GetMapping("/users/{id}")
    public Result<UserPublicVO> publicProfile(@PathVariable Long id) {
        return Result.success(userProfileService.getPublicProfile(id));
    }
}
