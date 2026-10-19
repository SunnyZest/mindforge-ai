package com.zest.mindforgeai.security;

import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.service.UserService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 从数据库加载用户及其角色（RBAC：sys_user → sys_user_role → sys_role）
 *
 * @author crx
 * @since 2026-10-03
 *
 *
 * 日志：Global AuthenticationManager configured with UserDetailsService bean with name customUserDetailsService

 * 流程是：
 * UserDetailsServiceAutoConfiguration 发现你有 UserDetailsService bean → 不创建默认的内存用户
 * InitializeUserDetailsBeanManagerConfigurer 把你的 CustomUserDetailsService + PasswordEncoder
 * 组装成 DaoAuthenticationProvider 注册进 ProviderManager

 *     DaoAuthenticationProvider 内部
 *     ① loadUserByUsername → 查库加载用户（ CustomUserDetailsService）
 *     ② 检查账号：isEnabled / isAccountNonLocked / isAccountNonExpired
 *     ③ passwordEncoder.matches(原始密码, 库里的 BCrypt 哈希)　← 密码校验在这
 *     ④ 检查密码是否过期：isCredentialsNonExpired
 *     ⑤ 构造已认证的 Authentication，并擦除密码
 *  返回 Authentication
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserService userService;

    public CustomUserDetailsService(UserService userService) {
        this.userService = userService;
    }

    /**
     * 登录时按标识加载（DaoAuthenticationProvider 走这个方法）。
     * <p>
     * ★ <b>支持「用户名或邮箱」两种登录标识</b>，规则只有两条：
     * <ol>
     *   <li>先按用户名查 —— <b>用户名优先</b>。所以即使某个人的用户名长得像邮箱，
     *       也不会被另一账号的 email 抢走</li>
     *   <li>用户名查不到、且输入里含 {@code @} 时，再按邮箱查</li>
     * </ol>
     * <p>
     * ⚠️ 为什么不做成两个接口 / 加一个 {@code loginType}：
     * 前端切换「用户名 / 邮箱」只是输入校验和文案的差别，后端自动识别能省掉
     * 「把 loginType 塞进 principal 或 ThreadLocal」那一层 —— 那种写法要么污染
     * {@code auth.getName()}，要么引入 ThreadLocal 清理负担，收益不值。
     * <p>
     * ⚠️ 管理端登录共用这个方法，等于管理员也多了一个「邮箱别名」。
     * 这不额外放权（角色仍从库里读），只是同一把密码多一种输入方式。
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userService.getByUsername(username);
        if (user == null && username != null && username.indexOf('@') > 0) {
            user = userService.getByEmail(username);
        }
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        return toLoginUser(user);
    }

    /** 解析 JWT 时按 userId 加载，token 不受用户名变更影响 */
    public LoginUser loadUserById(Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + userId);
        }
        return toLoginUser(user);
    }

    private LoginUser toLoginUser(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        // 角色：来自 sys_user_role → sys_role，加 ROLE_ 前缀供 hasRole / hasAnyRole 使用
        userService.listRoleCodes(user.getId())
                .forEach(code -> authorities.add(
                        new SimpleGrantedAuthority(RoleConstants.ROLE_PREFIX + code)));

        // 权限码：原样加入，供 hasAuthority('app:knowledge:read') / @PreAuthorize 使用
        userService.listPermissionCodes(user.getId())
                .forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));

        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),                                   // 库里的 BCrypt 密文
                user.getStatus() != null && user.getStatus() == 1,
                authorities);
    }
}
