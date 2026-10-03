package com.zest.mindforgeai.utils;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.security.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具。
 * <p>
 * 业务层用它获取当前用户，避免每次回库查询：
 * <pre>
 * Long userId = SecurityUtils.getUserId();
 * </pre>
 *
 * @author crx
 * @since 2026-10-03
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户，未登录抛 40101。
     * 适用于「必须登录」的接口。
     */
    public static LoginUser getLoginUser() {
        LoginUser loginUser = getLoginUserOrNull();
        if (loginUser == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    /** 获取当前用户 id（身份标识） */
    public static Long getUserId() {
        return getLoginUser().getUserId();
    }

    /** 获取当前用户名 */
    public static String getUsername() {
        return getLoginUser().getUsername();
    }

    /**
     * 未登录时返回 null，不抛异常。
     * 适用于「登录可选」的场景。
     */
    public static LoginUser getLoginUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof LoginUser loginUser ? loginUser : null;
    }
}
