package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.User;

import java.util.List;

/**
 * 用户服务
 *
 * @author crx
 * @since 2026-10-03
 */
public interface UserService extends IService<User> {

    /**
     * 注册：校验验证码 → 校验重名 → BCrypt 加密 → 落库 → 绑定默认 USER 角色
     * <p>
     * ★ 2026-10-18 起必须先过邮箱验证码（{@code scene = REGISTER}），
     * 落库时 {@code email_verified = 1}。
     *
     * @param code 邮箱验证码，校验成功后会被消费掉（一次性）
     * @return 已落库的用户（含自增 id）
     */
    User register(String username, String email, String code, String rawPassword);

    /**
     * 管理端代建账号（跳过验证码）。
     * <p>
     * ⚠️ 落库时 {@code email_verified = 0} —— 管理员代建没有「证明邮箱归属」这一步，
     * 记成已验证等于撒谎。
     */
    User createByAdmin(String username, String email, String rawPassword);

    /**
     * 找回密码（未登录）。
     * <p>
     * ⚠️ 邮箱<b>没注册过也返回成功</b>：否则这个接口就成了「邮箱是否注册」的探测器。
     * 验证码该消费还是消费（它确实发到了那个邮箱）。
     *
     * @param code 邮箱验证码，{@code scene = RESET_PASSWORD}
     */
    void resetPasswordByEmail(String email, String code, String newPassword);

    /** 按用户名查询，不存在返回 null */
    User getByUsername(String username);

    /** 查询用户的角色 code 列表（sys_user_role → sys_role） */
    List<String> listRoleCodes(Long userId);

    /** 查询用户的权限 code 列表（sys_user_role → sys_role_permission → sys_permission） */
    List<String> listPermissionCodes(Long userId);
}
