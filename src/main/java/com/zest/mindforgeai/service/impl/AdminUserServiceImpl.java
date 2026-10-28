package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.RoleMapper;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.mapper.UserRoleMapper;
import com.zest.mindforgeai.pojo.dto.AdminUserCreateDTO;
import com.zest.mindforgeai.pojo.dto.UserUpdateDTO;
import com.zest.mindforgeai.pojo.entity.SysRole;
import com.zest.mindforgeai.pojo.entity.SysUserRole;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.pojo.vo.AdminUserDetailVO;
import com.zest.mindforgeai.pojo.vo.AdminUserRoleVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.UserInfoVO;
import com.zest.mindforgeai.pojo.vo.UserResourceStatsVO;
import com.zest.mindforgeai.pojo.vo.UserRoleItemVO;
import com.zest.mindforgeai.pojo.vo.UserSummaryVO;
import com.zest.mindforgeai.service.AdminUserService;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.LoginAttemptService;
import com.zest.mindforgeai.service.RefreshTokenService;
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
import java.util.function.Function;
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

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。 */
    private static final Map<String, SFunction<User, ?>> SORTABLE = Map.of(
            "username", User::getUsername,
            "status", User::getStatus,
            "createdAt", User::getCreatedAt,
            "lastLoginAt", User::getLastLoginAt);

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final UserService userService;
    private final AuditService auditService;
    private final RoleHierarchyService roleHierarchyService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;

    public AdminUserServiceImpl(UserRoleMapper userRoleMapper,
                                RoleMapper roleMapper,
                                UserService userService,
                                AuditService auditService,
                                RoleHierarchyService roleHierarchyService,
                                RefreshTokenService refreshTokenService,
                                LoginAttemptService loginAttemptService) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.userService = userService;
        this.auditService = auditService;
        this.roleHierarchyService = roleHierarchyService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public PageResultVO<UserSummaryVO> pageUsers(long page, long pageSize,
                                                 String keyword, Integer status, String roleCode,
                                                 String sortBy, String sortOrder) {
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

        String safeKeyword = SortSupport.escapeLike(keyword);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(status != null, User::getStatus, status)
                .in(userIdsOfRole != null, User::getId, userIdsOfRole)
                // 关键词：用户名 / 昵称 / 邮箱 任一匹配
                .and(StringUtils.hasText(safeKeyword), w -> w
                        .like(User::getUsername, safeKeyword).or()
                        .like(User::getNickname, safeKeyword).or()
                        .like(User::getEmail, safeKeyword));

        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, User::getId, false);

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
    public AdminUserDetailVO getUserDetail(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 单用户详情，不存在 N+1 问题，按需查 3 次即可：
        // 角色明细（含角色名）、有效权限、资源占用（一条 SQL 查 5 张表）
        List<AdminUserRoleVO> roleItems = userRoleMapper.selectRolesByUserId(userId);
        AdminUserDetailVO vo = AdminUserDetailVO.of(user, roleItems,
                userService.listPermissionCodes(userId));

        UserResourceStatsVO stats = baseMapper.selectResourceStats(userId);
        vo.setKnowledgeBaseCount(stats == null ? 0 : stats.getKnowledgeBaseCount());
        vo.setDocumentCount(stats == null ? 0 : stats.getDocumentCount());
        vo.setResumeCount(stats == null ? 0 : stats.getResumeCount());
        vo.setInterviewCount(stats == null ? 0 : stats.getInterviewCount());
        vo.setJobCount(stats == null ? 0 : stats.getJobCount());
        vo.setStorageBytes(stats == null ? 0L : stats.getStorageBytes());

        // 登录失败计数是 Redis 里的实时值：登录成功会清零、key 到期自动消失，
        // 所以它回答的是「现在是不是正被猜密码」，不是历史累计次数。
        // 两个方法内部都不抛异常（Redis 不可用返回 0），详情页不会因此 500。
        vo.setFailedLoginCount(loginAttemptService.currentFailureCount(user.getUsername()));
        vo.setLoginLockRemainingSeconds(loginAttemptService.lockRemainingSeconds(user.getUsername()));

        return vo;
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

        // 手机号：只在【活跃账号】之间唯一。
        // ⚠️ 和邮箱的规则刻意不同 —— 号码是运营商资源，账号注销后应当能被新机主使用，
        //    所以查的是 deleted = 0 的那批，与数据库上的函数唯一索引 uk_user_phone 口径一致。
        // 空白归一成 null：不然两个「清空手机号」的账号会互相撞唯一索引（'' 也是值）
        String phone = StringUtils.hasText(dto.getPhone()) ? dto.getPhone().trim() : null;
        if (phone != null && !phone.equals(user.getPhone())
                && baseMapper.countByPhoneActive(phone, userId) > 0) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "手机号已被其他用户使用");
        }

        // MyBatis-Plus 默认策略：null 字段不进 UPDATE 语句 → 天然支持局部更新
        updateById(new User()
                .setId(userId)
                .setNickname(dto.getNickname())
                .setEmail(dto.getEmail())
                .setPhone(phone)
                .setAvatarUrl(dto.getAvatarUrl())
                .setUpdatedAt(LocalDateTime.now()));

        log.info("修改用户资料 userId={}", userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long userId, Integer status) {
        updateStatus(requireUser(userId), status);
    }

    /**
     * 批量场景用的重载：{@code user} 由调用方一次 {@code listByIds} 查出，这里不再查库。
     *
     * <p>★ 为什么要有它：批量方法如果在循环里 {@code getById}，就是典型 N+1 ——
     * 批量 50 个用户 = 50 次查询，而一次 {@code listByIds} 就够。
     * 「先批量查、再逐个处理」是消除循环查库最直接的办法。
     */
    private void updateStatus(User user, Integer status) {
        Long userId = user.getId();

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

        // 禁用账号时同步撤销该用户全部 refresh token，阻断其继续续期会话
        if (status != null && status != STATUS_ENABLED) {
            refreshTokenService.revokeAll(userId);
        }

        log.info("修改用户状态 userId={} status={}", userId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        assignRoles(requireUser(userId), roleIds);
    }

    /** 批量场景用的重载：{@code user} 已由调用方批量查出，不再查库（见 {@link #updateStatus(User, Integer)}） */
    private void assignRoles(User user, List<Long> roleIds) {
        Long userId = user.getId();

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVO createUser(AdminUserCreateDTO dto) {
        List<Long> targetRoleIds = dto.getRoleIds() == null ? List.of()
                : dto.getRoleIds().stream().filter(Objects::nonNull).distinct().toList();

        // ① 角色校验放在建账号【之前】。
        //    放在后面的话，角色非法时会先插一个用户再抛异常 ——
        //    事务虽然会回滚，但日志和报错会指向「用户已存在」之类的误导方向。
        if (!targetRoleIds.isEmpty()) {
            long found = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                    .in(SysRole::getId, targetRoleIds));
            if (found != targetRoleIds.size()) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "存在无效的角色 id");
            }

            // ★ 垂直越权防护：不能建出层级 ≥ 自己的角色。
            //   没有这一条，ADMIN 只要拿到 admin:user:create，
            //   就能直接建一个 SUPER_ADMIN 账号登录上去 —— 比改自己的角色还直接。
            for (Long roleId : targetRoleIds) {
                roleHierarchyService.requireRoleOperable(roleId);
            }
        }

        // ② 账号本身复用 UserService 的落库逻辑：唯一性检查（含已逻辑删除的）、BCrypt 加密、
        //    默认角色绑定都在那里。用户名 / 邮箱「一经使用即永久占用」的规则
        //    只该有一处实现，在这里重写一遍迟早会和那边走样。
        //    ⚠️ 用 createByAdmin 而不是 register —— 管理员代建没有验证码可验。
        User user = userService.createByAdmin(dto.getUsername(), dto.getEmail(), dto.getPassword());

        // ③ 昵称：管理员指定时覆盖 register 的默认值（默认与用户名相同）
        if (StringUtils.hasText(dto.getNickname())) {
            updateById(new User()
                    .setId(user.getId())
                    .setNickname(dto.getNickname())
                    .setUpdatedAt(LocalDateTime.now()));
            user.setNickname(dto.getNickname());
        }

        // ④ 角色：管理员显式指定时覆盖默认的 USER
        if (!targetRoleIds.isEmpty()) {
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, user.getId()));
            for (Long roleId : targetRoleIds) {
                userRoleMapper.insert(new SysUserRole()
                        .setUserId(user.getId())
                        .setRoleId(roleId));
            }
        }

        List<String> roles = userService.listRoleCodes(user.getId());
        log.info("管理员创建用户 userId={} username={} roles={}",
                user.getId(), user.getUsername(), roles);

        auditService.recordSuccess(AuditConstants.ACTION_USER_CREATE,
                AuditConstants.RESOURCE_USER, String.valueOf(user.getId()),
                Map.of("username", user.getUsername(), "roles", roles));

        return UserInfoVO.of(user, roles, userService.listPermissionCodes(user.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        deleteUser(requireUser(userId));
    }

    /** 批量场景用的重载：{@code user} 已由调用方批量查出，不再查库（见 {@link #updateStatus(User, Integer)}） */
    private void deleteUser(User user) {
        Long userId = user.getId();

        // 不能删自己：删完 token 里那个 userId 就查不到人了，等于把自己锁在外面
        if (userId.equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能删除当前登录的账号");
        }

        // ★ 垂直越权防护：不能删层级 ≥ 自己的用户
        roleHierarchyService.requireUserOperable(userId);

        // ① 先清角色关联 —— 表之间没有外键，主记录删了就再也找不回这些绑定，
        //    留着就是孤儿数据。与下面两步同在一个事务里。
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));

        // ② 逻辑删除账号：数据留着可追溯（审计里还引用着这个 userId），
        //    用户名 / 邮箱按唯一索引继续「永久占用」，防冒名。
        removeById(userId);

        // ③ 撤销全部 refresh token。
        //    不撤销的话，被删账号手里的 refreshToken 还能一直换新 accessToken。
        //    accessToken 那一侧靠 JwtAuthenticationFilter 拦 ——
        //    用户已经查不到了，就当无效 token 处理。
        refreshTokenService.revokeAll(userId);

        log.info("管理员删除用户 userId={} username={}", userId, user.getUsername());

        auditService.recordSuccess(AuditConstants.ACTION_USER_DELETE,
                AuditConstants.RESOURCE_USER, String.valueOf(userId),
                Map.of("username", user.getUsername()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateStatus(List<Long> ids, Integer status) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        // ★ 一次查出全部目标用户，循环里只做内存判断。
        //   逐个 getById 就是 N+1：批量 50 个用户 = 50 次查询。
        Map<Long, User> users = mapByIds(ids);
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (status != null && status == 0 && id.equals(SecurityUtils.getUserId())) {
                continue;
            }
            User user = users.get(id);
            if (user == null) {
                continue;
            }
            updateStatus(user, status);
            count++;
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchAssignRoles(List<Long> ids, List<Long> roleIds) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        Map<Long, User> users = mapByIds(ids);
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            User user = users.get(id);
            if (user == null) {
                continue;
            }
            assignRoles(user, roleIds);
            count++;
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteUsers(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        Map<Long, User> users = mapByIds(ids);
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (id.equals(SecurityUtils.getUserId())) {
                continue;
            }
            User user = users.get(id);
            if (user == null) {
                continue;
            }
            deleteUser(user);
            count++;
        }
        return count;
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 按 id 批量查用户并转成 Map —— 批量操作的公共前置。
     *
     * <p>存在的才进 Map，调用方 {@code users.get(id) == null} 就代表「这个 id 不存在」，
     * 不需要再查一次确认。过滤 null 与去重，避免 {@code IN ()} 语法错误和重复查询。
     */
    private Map<Long, User> mapByIds(List<Long> ids) {
        List<Long> validIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (validIds.isEmpty()) {
            return Map.of();
        }
        return listByIds(validIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
    }

    /** 查单个用户，不存在就抛 404 —— 单条操作的公共前置 */
    private User requireUser(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

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
