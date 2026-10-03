package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.common.exception.BizException;
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

    public UserServiceImpl(PasswordEncoder passwordEncoder,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper) {
        this.passwordEncoder = passwordEncoder;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
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

        // 绑定默认角色 USER（mindforge-schema §11 已内置该角色）,自动分配相应权限
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
        List<Long> roleIds = userRoleMapper
                .selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();

        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getStatus, 1))
                .stream()
                .map(SysRole::getCode)
                .toList();
    }
}
