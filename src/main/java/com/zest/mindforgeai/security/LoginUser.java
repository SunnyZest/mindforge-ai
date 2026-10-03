package com.zest.mindforgeai.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 登录用户主体：同时携带 userId（身份标识）与 username（展示 / 审计用）。
 * <p>
 * 作为 Spring Security 的 principal 存入 SecurityContext，
 * 业务层通过 {@code SecurityUtils.getUserId()} 零查库获取当前用户。
 *
 * @author crx
 * @since 2026-10-03
 */
@Getter
public class LoginUser implements UserDetails {

    /** 用户 id，身份标识（对应 JWT 的 sub） */
    private final Long userId;

    /** 用户名，展示与审计用 */
    private final String username;

    /** BCrypt 密文，仅登录校验阶段使用 */
    private final String password;

    /** 账号是否启用（sys_user.status == 1） */
    private final boolean enabled;

    /** 角色权限（ROLE_ 前缀） */
    private final Collection<? extends GrantedAuthority> authorities;

    public LoginUser(Long userId,
                     String username,
                     String password,
                     boolean enabled,
                     Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.authorities = authorities == null ? List.of() : authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
