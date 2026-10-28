package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.common.constant.VerificationConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.PermissionMapper;
import com.zest.mindforgeai.mapper.RoleMapper;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.mapper.UserRoleMapper;
import com.zest.mindforgeai.pojo.entity.SysRole;
import com.zest.mindforgeai.pojo.entity.SysUserRole;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.RefreshTokenService;
import com.zest.mindforgeai.service.UserService;
import com.zest.mindforgeai.service.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用户服务实现
 *
 * @author crx
 * @since 2026-10-03
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /** user.last_login_ip 的列宽，写库前按它截断 */
    private static final int MAX_IP_LEN = 64;

    private final PasswordEncoder passwordEncoder;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final VerificationCodeService verificationCodeService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public UserServiceImpl(PasswordEncoder passwordEncoder,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper,
                           PermissionMapper permissionMapper,
                           VerificationCodeService verificationCodeService,
                           RefreshTokenService refreshTokenService,
                           AuditService auditService) {
        this.passwordEncoder = passwordEncoder;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.verificationCodeService = verificationCodeService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User register(String username, String email, String code, String rawPassword) {
        // ★ 先验证邮箱：注册必须先证明「你拥有这个邮箱」。
        //   顺序很关键 —— 放在重名检查之前，否则「用户名是否被占用」就成了
        //   不用验证码就能探测的信息（拿别人的邮箱也能试）。
        String normalizedEmail = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        verificationCodeService.verifyAndConsume(
                VerificationConstants.CHANNEL_EMAIL,
                normalizedEmail,
                VerificationConstants.SCENE_REGISTER,
                code);

        // 验证码刚验过，所以是「已验证」
        return doCreate(username, normalizedEmail, rawPassword, 1);
    }

    /**
     * 管理端代建账号。
     * <p>
     * ★ <b>跳过验证码</b>：管理员代建是可信动作，没有「证明邮箱归属」这一步。
     * 所以 {@code email_verified} 记 <b>0</b> —— 这个邮箱确实没被证明过，
     * 记 1 等于撒谎（以后真要做「验证邮箱才能找回密码」时会踩到）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public User createByAdmin(String username, String email, String rawPassword) {
        return doCreate(username, normalizeEmail(email), rawPassword, 0);
    }

    /** 落库 + 绑定默认角色。唯一性检查也在这里 —— 只此一处，别在别处再写一遍 */
    private User doCreate(String username, String normalizedEmail,
                          String rawPassword, int emailVerified) {
        // 用「包含已逻辑删除」的统计做唯一性检查：
        // user 表有 uk_user_username / uk_user_email 唯一索引，而唯一约束不管逻辑删除，
        // 所以用户名和邮箱是【永久占用】的 —— 已注销账号的名字也不能再注册（类似 GitHub，防冒名）。
        // 若改用 MP 的 getByUsername()（自动加 deleted = 0），会漏掉已删除的记录，
        // 检查通过后直接撞索引，只能靠 GlobalExceptionHandler 兜底给个模糊提示。
        if (baseMapper.countByUsernameIgnoreDeleted(username) > 0) {
            throw new BizException(ErrorCode.USER_ALREADY_EXISTS, "用户名已存在");
        }
        if (StringUtils.hasText(normalizedEmail)
                && baseMapper.countByEmailIgnoreDeleted(normalizedEmail) > 0) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "邮箱已被使用");
        }

        User user = new User()
                .setUsername(username)
                .setEmail(normalizedEmail)
                .setEmailVerified(emailVerified)
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

    /** 邮箱统一小写：{@code Foo@QQ.com} 和 {@code foo@qq.com} 是同一个邮箱，不归一化会绕过唯一性 */
    private String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 找回密码。
     * <p>
     * ★ <b>邮箱没注册过也返回成功</b>：否则这个公开接口就成了「这个邮箱注册过没有」的探测器。
     * 验证码照常消费（它确实发到了那个邮箱，不用就浪费了）。
     * <p>
     * ⚠️ 改完密码要<b>撤销该用户全部 refreshToken</b>：密码都丢了，说明账号可能已经被人登着，
     * 不撤销的话对方能一直续期下去（access token 30 分钟，但 refresh 能续命）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPasswordByEmail(String email, String code, String newPassword) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        verificationCodeService.verifyAndConsume(
                VerificationConstants.CHANNEL_EMAIL,
                normalizedEmail,
                VerificationConstants.SCENE_RESET_PASSWORD,
                code);

        // MP 的逻辑删除会自动加 deleted = 0，已注销的账号不能被找回（它的邮箱也没法注册新号 ——
        // 邮箱是永久占用的，这算已知的边角情况，先如实记录）
        User user = getOne(new LambdaQueryWrapper<User>().eq(User::getEmail, normalizedEmail));
        if (user == null) {
            log.info("找回密码：邮箱未注册，静默返回成功（不泄露注册状态）");
            return;
        }

        updateById(new User()
                .setId(user.getId())
                .setPasswordHash(passwordEncoder.encode(newPassword))
                .setUpdatedAt(LocalDateTime.now()));

        refreshTokenService.revokeAll(user.getId());

        auditService.recordSuccess(AuditConstants.ACTION_USER_RESET_PASSWORD,
                AuditConstants.RESOURCE_USER, String.valueOf(user.getId()),
                Map.of("byEmail", true));

        log.info("找回密码成功 userId={}（已撤销全部 refreshToken）", user.getId());
    }

    @Override
    public User getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    @Override
    public User getByEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            // 列可空（管理端代建可能没填），空串不该去撞唯一索引
            return null;
        }
        return getOne(new LambdaQueryWrapper<User>().eq(User::getEmail, normalized));
    }

    @Override
    public List<String> listRoleCodes(Long userId) {
        return roleMapper.selectCodesByUserId(userId);
    }

    @Override
    public List<String> listPermissionCodes(Long userId) {
        return permissionMapper.selectCodesByUserId(userId);
    }

    /**
     * 只写 last_login_at / last_login_ip 两个列 —— 不碰 updated_at（登录不是资料变更，见接口注释）。
     * <p>
     * 吞掉异常：登录本身已经成功了，把「记录最后登录信息」失败放大成登录失败没有道理。
     * 代价是这个列偶尔会落后一次，但那比登不进去好得多。
     * <p>
     * IP 为空时只更新时间：{@code updateById} 只拼非 null 字段，
     * 所以传 null 的 lastLoginIp 不会把上一次记下来的 IP 清掉 —— 这正是想要的，
     * 不能因为某次取不到 IP 就把历史线索抹掉。
     */
    @Override
    public void touchLastLogin(Long userId, String clientIp) {
        if (userId == null) {
            return;
        }
        try {
            updateById(new User()
                    .setId(userId)
                    .setLastLoginAt(LocalDateTime.now())
                    .setLastLoginIp(truncateIp(clientIp)));
        } catch (Exception e) {
            log.error("更新最后登录信息失败 userId={}", userId, e);
        }
    }

    /**
     * IP 截断到列宽（64）。
     * <p>
     * 与 {@code AuditServiceImpl.truncate} 同一个理由：来源不可控的字符串写库前必须截断，
     * 否则严格模式下是 SQL 报错，非严格模式下是静默截断 —— 后者更难查。
     */
    private String truncateIp(String clientIp) {
        if (!StringUtils.hasText(clientIp)) {
            return null;
        }
        String ip = clientIp.trim();
        return ip.length() > MAX_IP_LEN ? ip.substring(0, MAX_IP_LEN) : ip;
    }
}
