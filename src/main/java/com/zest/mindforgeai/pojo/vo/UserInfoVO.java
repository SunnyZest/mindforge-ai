package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 用户信息
 *
 * @author crx
 * @since 2026-10-03
 */
@Schema(description = "用户信息")
@Data
public class UserInfoVO {

    @Schema(description = "用户 id（字符串，避免 JS 精度丢失，文档 §8.6）")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    /**
     * 头像地址。
     * <p>
     * ⚠️ 是<b>后端路径</b> {@code /api/app/avatars/{fileId}}，前端直接塞进 {@code <img src>} ——
     * 这个读取接口是公开的（img 带不上 Authorization 头），每次请求 302 跳到现签的 OSS URL。
     * 没设置过头像时为 {@code null}。
     * <p>
     * 加在这里是为了让<b>登录 / 刷新页面</b>就能拿到头像：顶栏只需要 {@code /app/auth/me}，
     * 不必再单独请求一次个人资料接口。
     */
    @Schema(description = "头像地址（后端路径，未设置为 null）")
    private String avatarUrl;

    @Schema(description = "角色 code 列表")
    private List<String> roles;

    @Schema(description = "权限码列表")
    private List<String> permissions;

    public static UserInfoVO of(User user, List<String> roles, List<String> permissions) {
        UserInfoVO vo = new UserInfoVO();
        vo.setId(String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setRoles(roles);
        vo.setPermissions(permissions);
        return vo;
    }
}
