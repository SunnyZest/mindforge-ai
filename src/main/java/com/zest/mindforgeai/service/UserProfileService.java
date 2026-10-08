package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.ChangePasswordDTO;
import com.zest.mindforgeai.pojo.dto.UserProfileUpdateDTO;
import com.zest.mindforgeai.pojo.vo.UserProfileVO;
import com.zest.mindforgeai.pojo.vo.UserPublicVO;

/**
 * 用户自己的资料。
 * <p>
 * 与 {@link AdminUserService} 分开：那个是<b>管理员视角</b>的跨用户操作，
 * 能改任意用户、还能禁用和分配角色；这里<b>只能操作当前登录用户自己</b> ——
 * 目标用户 id 一律从 SecurityContext 取，<b>不从入参取</b>，
 * 所以不存在「改个 id 就改到别人」的可能。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface UserProfileService {

    /** 当前登录用户的资料 */
    UserProfileVO getProfile();

    /** 修改当前登录用户的昵称 / 头像，null 字段表示不修改 */
    UserProfileVO updateProfile(UserProfileUpdateDTO dto);

    /**
     * 修改当前登录用户的密码。
     * <p>
     * 需要提供原密码，改完会撤销该用户<b>全部</b> refresh token
     * （密码换了，之前发出去的所有会话都该失效）。
     */
    void changePassword(ChangePasswordDTO dto);

    /**
     * 用户公开信息（仅 id / username / nickname / avatarUrl）。
     * <p>
     * 这个接口的 id 是别人可以随便猜的，所以只返回能公开的字段。
     */
    UserPublicVO getPublicProfile(Long userId);
}
