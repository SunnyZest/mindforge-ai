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

    @Schema(description = "角色 code 列表")
    private List<String> roles;

    @Schema(description = "权限码列表")
    private List<String> permissions;

    public static UserInfoVO of(User user, List<String> roles, List<String> permissions) {
        UserInfoVO vo = new UserInfoVO();
        vo.setId(String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRoles(roles);
        vo.setPermissions(permissions);
        return vo;
    }
}
