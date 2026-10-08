package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户公开信息，文档 §十二「GET /api/app/users/{id}」。
 * <p>
 * ⚠️ <b>只放能公开的字段</b>：邮箱、手机号、状态、注册时间一律不给 ——
 * 这个接口的入参是别人可以随便猜的 id，返回什么就等于泄漏什么。
 * <p>
 * 所以这里不是「把 User 转成 VO」，而是<b>显式白名单</b>：
 * 将来给 user 表加字段时，不会因为忘了排除而意外暴露出去。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "用户公开信息")
@Data
public class UserPublicVO {

    @Schema(description = "用户 id（字符串，避免 JS 精度丢失，文档 §8.6）")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像地址")
    private String avatarUrl;

    public static UserPublicVO of(User user) {
        UserPublicVO vo = new UserPublicVO();
        vo.setId(String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        return vo;
    }
}
