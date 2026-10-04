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
     * 注册：校验重名 → BCrypt 加密 → 落库 → 绑定默认 USER 角色
     *
     * @return 已落库的用户（含自增 id）
     */
    User register(String username, String email, String rawPassword);

    /** 按用户名查询，不存在返回 null */
    User getByUsername(String username);

    /** 查询用户的角色 code 列表（sys_user_role → sys_role） */
    List<String> listRoleCodes(Long userId);

    /** 查询用户的权限 code 列表（sys_user_role → sys_role_permission → sys_permission） */
    List<String> listPermissionCodes(Long userId);
}
