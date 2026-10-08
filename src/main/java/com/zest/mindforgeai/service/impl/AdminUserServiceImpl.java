package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.RoleMapper;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.mapper.UserRoleMapper;
import com.zest.mindforgeai.pojo.dto.UserUpdateDTO;
import com.zest.mindforgeai.pojo.entity.SysRole;
import com.zest.mindforgeai.pojo.entity.SysUserRole;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.UserInfoVO;
import com.zest.mindforgeai.pojo.vo.UserRoleItemVO;
import com.zest.mindforgeai.pojo.vo.UserSummaryVO;
import com.zest.mindforgeai.service.AdminUserService;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.RoleHierarchyService;
import com.zest.mindforgeai.service.UserService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理端用户管理服务实现
 *
 * @author crx
 * @since 2026-10-04
 */
@Slf4j
@Service
public class AdminUserServiceImpl extends ServiceImpl<UserMapper, User> implements AdminUserService {

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    /** 用户状态：1 启用 / 0 禁用 */
    private static final int STATUS_ENABLED = 1;

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final UserService userService;
    private final AuditService auditService;
    private final RoleHierarchyService roleHierarchyService;

    public AdminUserServiceImpl(UserRoleMapper userRoleMapper,
                                RoleMapper roleMapper,
                                UserService userService,
                                AuditService auditService,
                                RoleHierarchyService roleHierarchyService) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.userService = userService;
        this.auditService = auditService;
        this.roleHierarchyService = roleHierarchyService;
    }

    @Override
    public PageResultVO<UserSummaryVO> pageUsers(long page, long pageSize,
                                                 String keyword, Integer status, String roleCode) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        // 按角色筛选：先查出该角色下的 userId 再作为 IN 条件。
        // 不直接把 roleCode 拼进 SQL —— 那样会有注入风险。
        List<Long> userIdsOfRole = null;
        if (StringUtils.hasText(roleCode)) {
            // 先查role信息
            SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getCode, roleCode));
            if (role == null) {
                return PageResultVO.empty(safePage, safeSize);
            }
            // 拿到role，通过role.getId拿到所有的user列表,再拿userId查role对应的user
            userIdsOfRole = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                            .eq(SysUserRole::getRoleId, role.getId()))
                    .stream()
                    .map(SysUserRole::getUserId)
                    .toList();
            if (userIdsOfRole.isEmpty()) {
                return PageResultVO.empty(safePage, safeSize);
            }
        }

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(status != null, User::getStatus, status)
                .in(userIdsOfRole != null, User::getId, userIdsOfRole)
                // 关键词：用户名 / 昵称 / 邮箱 任一匹配
                .and(StringUtils.hasText(keyword), w -> w
                        .like(User::getUsername, keyword).or()
                        .like(User::getNickname, keyword).or()
                        .like(User::getEmail, keyword))
                .orderByDesc(User::getId);

        Page<User> result = page(new Page<>(safePage, safeSize), wrapper);

        // 一次性查出整页用户的角色，避免逐行查询造成 N+1
        Map<Long, List<String>> roleMap = mapRoleCodesByUserIds(
                result.getRecords().stream().map(User::getId).toList());

        List<UserSummaryVO> records = result.getRecords().stream()
                .map(u -> UserSummaryVO.of(u, roleMap.getOrDefault(u.getId(), List.of())))
                .toList();

        return PageResultVO.of(result, records);
    }

    @Override
    public UserInfoVO getUserDetail(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        // 单用户详情，直接查两次即可（不是列表，不存在 N+1）
        return UserInfoVO.of(user,
                userService.listRoleCodes(userId),
                userService.listPermissionCodes(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long userId, UserUpdateDTO dto) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 改了邮箱才做唯一性校验。
        // 用「包含已逻辑删除」的统计 —— uk_user_email 唯一约束不管逻辑删除，邮箱是永久占用的。
        // 这里无需排除自己：上面已确保新邮箱与当前邮箱不同，统计结果必然不含自己。
        if (StringUtils.hasText(dto.getEmail()) && !dto.getEmail().equals(user.getEmail())
                && baseMapper.countByEmailIgnoreDeleted(dto.getEmail()) > 0) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "邮箱已被其他用户使用");
        }

        // MyBatis-Plus 默认策略：null 字段不进 UPDATE 语句 → 天然支持局部更新
        updateById(new User()
                .setId(userId)
                .setNickname(dto.getNickname())
                .setEmail(dto.getEmail())
                .setPhone(dto.getPhone())
                .setAvatarUrl(dto.getAvatarUrl())
                .setUpdatedAt(LocalDateTime.now()));

        log.info("修改用户资料 userId={}", userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long userId, Integer status) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 不允许禁用自己：否则管理员可能把自己锁在外面，再也登不进来
        if (status != null && status != STATUS_ENABLED
                && userId.equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能禁用当前登录的账号");
        }

        // ★ 垂直越权防护：不能禁用层级 ≥ 自己的用户。
        // 否则 ADMIN（有 admin:user:disable 权限）就能把 SUPER_ADMIN 的账号禁掉，
        // 系统会失去唯一能改权限的人 —— 权限码层面他是「有权限」的，但层级上不该动。
        roleHierarchyService.requireUserOperable(userId);

        updateById(new User()
                .setId(userId)
                .setStatus(status)
                .setUpdatedAt(LocalDateTime.now()));

        log.info("修改用户状态 userId={} status={}", userId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        List<Long> targetRoleIds = roleIds == null ? List.of()
                : roleIds.stream().filter(Objects::nonNull).distinct().toList();

        // 校验角色都存在（没有外键，否则会插入孤儿数据）
        if (!targetRoleIds.isEmpty()) {
            long found = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                    .in(SysRole::getId, targetRoleIds));
            if (found != targetRoleIds.size()) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "存在无效的角色 id");
            }
        }

        // ★ 垂直越权防护，两个方向都要卡：
        //   ① 不能改层级 ≥ 自己的用户（否则能去动比自己高的账号）
        //   ② 不能分配层级 ≥ 自己的角色（否则能给自己或别人提权）
        //      —— 这一条尤其关键：没有它，ADMIN 只要能调这个接口，
        //         就能把 SUPER_ADMIN 角色分给自己或同伙，实现提权。
        roleHierarchyService.requireUserOperable(userId);
        for (Long roleId : targetRoleIds) {
            roleHierarchyService.requireRoleOperable(roleId);
        }

        // 拿到角色对应的code：“User”、"admin"
        List<String> before = userService.listRoleCodes(userId);

        // ⚠️ 没有外键级联：必须手动「先清后插」，且与下面两步同在一个事务里
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));
        for (Long roleId : targetRoleIds) {
            // 分配角色
            userRoleMapper.insert(new SysUserRole()
                    .setUserId(userId)
                    .setRoleId(roleId));
        }

        // 分配后的角色
        List<String> after = userService.listRoleCodes(userId);
        log.info("分配角色 userId={} {} -> {}", userId, before, after);

        // 审计：记录新旧角色对比（这是审计最有价值的部分）
        auditService.recordSuccess(AuditConstants.ACTION_USER_ASSIGN_ROLE,
                AuditConstants.RESOURCE_USER, String.valueOf(userId),
                Map.of("username", user.getUsername(), "before", before, "after", after));
    }

    /* ---------- 内部工具 ---------- */

    /** 批量查角色并分组：userId → [roleCode, ...] */
    private Map<Long, List<String>> mapRoleCodesByUserIds(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRoleMapper.selectRoleCodesByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserRoleItemVO::getUserId,
                        Collectors.mapping(UserRoleItemVO::getRoleCode, Collectors.toList())));
    }
}
