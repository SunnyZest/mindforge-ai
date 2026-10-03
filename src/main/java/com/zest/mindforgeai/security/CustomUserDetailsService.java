package com.zest.mindforgeai.security;

import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.service.UserService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

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

    /** 登录时按用户名加载（DaoAuthenticationProvider 走这个方法） */
    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userService.getByUsername(username);
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
        // 角色来自 sys_user_role → sys_role；sys_user 表没有 role 列
        List<SimpleGrantedAuthority> authorities = userService.listRoleCodes(user.getId())
                .stream()
                .map(code -> new SimpleGrantedAuthority(RoleConstants.ROLE_PREFIX + code))
                .toList();

        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),                                   // 库里的 BCrypt 密文
                user.getStatus() != null && user.getStatus() == 1,
                authorities);
    }
}
