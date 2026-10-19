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

    /**
     * 按邮箱查询（登录用），不存在返回 null。
     * <p>
     * ⚠️ 三个约定都和 {@link #getByUsername} 不同，别混用：
     * <ul>
     *   <li>入参会 <b>trim + 转小写</b> —— 用户输 {@code Foo@Bar.com} 也能登</li>
     *   <li>email 为 blank 直接返回 null（列本身可空，管理端代建可能没填）</li>
     *   <li>逻辑删除的账号查不到（MP 的 {@code deleted = 0} 自动兜住）</li>
     * </ul>
     * 刻意<b>不</b>要求 {@code email_verified = 1}：这个字段的语义是「注册时验过码」，
     * 不是「可用于登录」。早期账号（含 admin）该列都是 0，要求它等于把这些人拒之门外。
     */
    User getByEmail(String email);

    /** 查询用户的角色 code 列表（sys_user_role → sys_role） */
    List<String> listRoleCodes(Long userId);

    /** 查询用户的权限 code 列表（sys_user_role → sys_role_permission → sys_permission） */
    List<String> listPermissionCodes(Long userId);
}
