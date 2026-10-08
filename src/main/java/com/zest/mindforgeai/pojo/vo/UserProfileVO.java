package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人资料。
 * <p>
 * 与 {@link UserInfoVO} 的区别：{@code UserInfoVO} 是「认证视角」——
 * 只给身份和权限，登录、刷新、/auth/me 都用它；
 * 本类面向「资料页」，多出邮箱、手机号、头像、注册时间这些展示字段。
 * 两者刻意分开：认证接口的响应体越小越好，不该顺带把用户资料全吐出去。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "个人资料")
@Data
public class UserProfileVO {

    @Schema(description = "用户 id（字符串，避免 JS 精度丢失，文档 §8.6）")
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

    @Schema(description = "1 正常 / 0 禁用")
    private Integer status;

    @Schema(description = "注册时间")
    private LocalDateTime createdAt;

    public static UserProfileVO of(User user) {
        UserProfileVO vo = new UserProfileVO();
        vo.setId(String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
