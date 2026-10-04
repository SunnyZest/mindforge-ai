package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端用户列表项。
 * <p>
 * 比 {@link UserInfoVO} 多带管理端需要的字段（邮箱、状态、最后登录时间），
 * 但<b>不含 permissions</b> —— 列表页不需要，也不该为每行都查一次权限（N+1）。
 *
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "管理端用户列表项")
@Data
public class UserSummaryVO {

    @Schema(description = "用户 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像地址")
    private String avatarUrl;

    @Schema(description = "状态：1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "角色 code 列表")
    private List<String> roles;

    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginAt;

    @Schema(description = "注册时间")
    private LocalDateTime createdAt;

    public static UserSummaryVO of(User user, List<String> roles) {
        UserSummaryVO vo = new UserSummaryVO();
        vo.setId(user.getId() == null ? null : String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setStatus(user.getStatus());
        vo.setRoles(roles == null ? List.of() : roles);
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
