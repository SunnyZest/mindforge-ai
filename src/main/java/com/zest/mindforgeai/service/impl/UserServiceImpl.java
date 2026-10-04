package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.PermissionMapper;
import com.zest.mindforgeai.mapper.RoleMapper;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.mapper.UserRoleMapper;
import com.zest.mindforgeai.pojo.entity.SysRole;
import com.zest.mindforgeai.pojo.entity.SysUserRole;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户服务实现
 *
 * @author crx
 * @since 2026-10-03
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    public UserServiceImpl(PasswordEncoder passwordEncoder,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper,
                           PermissionMapper permissionMapper) {
        this.passwordEncoder = passwordEncoder;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User register(String username, String email, String rawPassword) {
        if (getByUsername(username) != null) {
            throw new BizException(ErrorCode.USER_ALREADY_EXISTS, "用户名已存在");
        }

        User user = new User()
                .setUsername(username)
                .setEmail(email)
                .setPasswordHash(passwordEncoder.encode(rawPassword))
                .setNickname(username)
                .setStatus(1)
                .setDeleted(0);
        save(user);

        // 绑定默认角色 USER（mindforge-schema §11 已内置该角色），权限通过角色间接获得
        SysRole defaultRole = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, RoleConstants.USER));
        if (defaultRole != null) {
            userRoleMapper.insert(new SysUserRole()
                    .setUserId(user.getId())
                    .setRoleId(defaultRole.getId()));
        }
        return user;
    }

    @Override
    public User getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    @Override
    public List<String> listRoleCodes(Long userId) {
        return roleMapper.selectCodesByUserId(userId);
    }

    @Override
    public List<String> listPermissionCodes(Long userId) {
        return permissionMapper.selectCodesByUserId(userId);
    }
}
