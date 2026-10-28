package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.ChangePasswordDTO;
import com.zest.mindforgeai.pojo.dto.LoginDTO;
import com.zest.mindforgeai.pojo.dto.RefreshTokenDTO;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.pojo.vo.LoginVO;
import com.zest.mindforgeai.pojo.vo.TokenVO;
import com.zest.mindforgeai.pojo.vo.UserInfoVO;
import com.zest.mindforgeai.security.LoginUser;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.LoginAttemptService;
import com.zest.mindforgeai.service.RefreshTokenService;
import com.zest.mindforgeai.service.SecondaryConfirmService;
import com.zest.mindforgeai.service.UserProfileService;
import com.zest.mindforgeai.service.UserService;
import com.zest.mindforgeai.utils.ClientIpResolver;
import com.zest.mindforgeai.utils.JwtUtil;
import com.zest.mindforgeai.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端认证，对外路径 {@code /api/admin/auth/**}（文档 §4.2、§十一）。
 *
 * @author crx
 * @since 2026-10-03
 */
@Slf4j
@Tag(name = "管理端-认证")
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final ClientIpResolver ipResolver;
    private final SecondaryConfirmService secondaryConfirmService;
    private final UserProfileService userProfileService;

    public AdminAuthController(AuthenticationManager authenticationManager,
                               JwtUtil jwtUtil,
                               UserService userService,
                               AuditService auditService,
                               RefreshTokenService refreshTokenService,
                               LoginAttemptService loginAttemptService,
                               ClientIpResolver ipResolver,
                               SecondaryConfirmService secondaryConfirmService,
                               UserProfileService userProfileService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.ipResolver = ipResolver;
        this.secondaryConfirmService = secondaryConfirmService;
        this.userProfileService = userProfileService;
    }

    @Operation(summary = "管理员登录")
    @SecurityRequirements   // 公开接口：登录无需认证，摘掉文档上的锁标记
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        String clientIp = ipResolver.resolve(request);

        // 先看有没有被锁 —— 管理端入口尤其重要，被爆破的后果比用户端严重
        loginAttemptService.checkNotLocked(dto.getUsername(), clientIp);

        /**
         * Authentication auth = authenticationManager.authenticate(...);
         * // ① 能走到下一行，就说明认证已通过（失败已经抛异常了）
         * // ② auth.getName()        → username
         * // ③ auth.getPrincipal()   → LoginUser（含 userId！）
         * // ④ auth.getAuthorities() → 角色 + 权限
         */
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));
        } catch (AuthenticationException e) {
            // 记失败次数 —— 连续多次会锁定账号 / IP
            loginAttemptService.recordFailure(dto.getUsername(), clientIp);

            // 登录失败也要留痕：此时尚未认证成功，SecurityContext 为空，userId 记 null，
            // 把「尝试的用户名」放进 detail，便于排查撞库 / 爆破
            auditService.recordFailure(AuditConstants.ACTION_ADMIN_LOGIN,
                    AuditConstants.RESOURCE_USER, null,
                    Map.of("attemptedUsername", dto.getUsername(),
                           "clientIp", clientIp,
                           "reason", e.getClass().getSimpleName()));
            throw e;    // 继续抛出，交给 GlobalExceptionHandler 转成 40101
        }

        // 密码对了，清掉失败计数。
        // 注意：这里在「角色校验」之前清 —— 角色不够不算爆破，
        // 不该让普通用户误登管理端累积成锁定。
        loginAttemptService.recordSuccess(dto.getUsername(), clientIp);

        // ★ 认证成功 → 写入 SecurityContext。
        //   authenticate() 只返回 Authentication，并不会设置 SecurityContext，
        //   这里补上之后，本次请求后续的逻辑（含审计取 userId）都能正常拿到当前用户。
        SecurityContextHolder.getContext().setAuthentication(auth);

        // ⚠️ 同用户端：邮箱登录时 auth.getName() 是邮箱，不能按用户名查。
        //    管理端没有邮箱登录入口，但认证层是共用的 —— 不这样写会留下 NPE 隐患。
        LoginUser principal = (LoginUser) auth.getPrincipal();
        User user = userService.getById(principal.getUserId());
        List<String> roles = userService.listRoleCodes(user.getId());

        // 管理端入口必须校验角色，否则普通用户也能从这里拿到 token（文档 §7.3）
        boolean isAdmin = roles.stream().anyMatch(code ->
                RoleConstants.ADMIN.equals(code) || RoleConstants.SUPER_ADMIN.equals(code));
        if (!isAdmin) {
            auditService.recordFailure(AuditConstants.ACTION_ADMIN_LOGIN,
                    AuditConstants.RESOURCE_USER, String.valueOf(user.getId()),
                    Map.of("reason", "该账号无管理端登录权限", "roles", roles));
            throw new BizException(ErrorCode.FORBIDDEN, "该账号无管理端登录权限");
        }

        auditService.recordSuccess(AuditConstants.ACTION_ADMIN_LOGIN,
                AuditConstants.RESOURCE_USER, String.valueOf(user.getId()),
                Map.of("username", user.getUsername(), "roles", roles));

        // 记录最后登录时间与 IP。放在角色校验【之后】——
        // 普通用户误登管理端入口不算「登录成功」，不该刷新这条记录。
        userService.touchLastLogin(user.getId(), clientIp);

        log.info("管理员登录成功 userId={} username={} roles={}",
                user.getId(), user.getUsername(), roles);

        LoginVO vo = new LoginVO();
        vo.setAccessToken(jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles));
        vo.setRefreshToken(refreshTokenService.issue(user.getId()));
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        vo.setUser(UserInfoVO.of(user, roles,
                userService.listPermissionCodes(user.getId())));
        return Result.success(vo);
    }

    /**
     * 获取当前登录管理员信息。
     * <p>
     * 与用户端 {@code /app/auth/me} 对称。由于 {@code /admin/**} 要求 ADMIN / SUPER_ADMIN，
     * 普通用户访问这里会先被 SecurityConfig 拦成 403，而不是 401。
     */
    @Operation(summary = "获取当前登录管理员信息")
    @GetMapping("/me")
    public Result<UserInfoVO> me() {
        Long userId = SecurityUtils.getUserId();

        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        List<String> roles = userService.listRoleCodes(userId);
        List<String> permissions = userService.listPermissionCodes(userId);

        log.debug("查询当前登录管理员信息 userId={} username={} roles={} permissions={}",
                userId, user.getUsername(), roles, permissions.size());

        return Result.success(UserInfoVO.of(user, roles, permissions));
    }

    /**
     * 刷新 access token（与用户端 {@code /app/auth/refresh} 逻辑一致）。
     * <p>
     * 公开接口：access token 已过期，只能靠 body 里的 refreshToken 认证。
     * 轮换策略：旧的 refreshToken 立即作废。
     */
    @Operation(summary = "刷新 access token")
    @SecurityRequirements   // 公开接口：access token 已过期，靠 refreshToken 认证
    @PostMapping("/refresh")
    public Result<TokenVO> refresh(@Valid @RequestBody RefreshTokenDTO dto) {
        RefreshTokenService.RefreshResult result =
                refreshTokenService.refresh(dto.getRefreshToken());

        User user = userService.getById(result.userId());
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        List<String> roles = userService.listRoleCodes(result.userId());

        log.debug("刷新 access token（管理端）userId={} username={}",
                result.userId(), user.getUsername());

        TokenVO vo = new TokenVO();
        vo.setAccessToken(jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles));
        vo.setRefreshToken(result.refreshToken());
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        return Result.success(vo);
    }

    /**
     * 管理员登出（与用户端 {@code /app/auth/logout} 逻辑一致）。
     * <p>
     * 撤销 body 里的 refreshToken，不依赖 access token。
     */
    @Operation(summary = "管理员登出")
    @SecurityRequirements   // 公开接口：靠 body 里的 refreshToken 认证
    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody RefreshTokenDTO dto) {
        refreshTokenService.revoke(dto.getRefreshToken());
        log.info("管理员登出");
        return Result.success();
    }

    /**
     * 获取高危操作二次确认凭据。
     */
    @Operation(summary = "获取高危操作二次确认凭据")
    @PostMapping("/confirm-token")
    public Result<com.zest.mindforgeai.pojo.vo.ConfirmTokenVO> issueConfirmToken(
            @Valid @RequestBody com.zest.mindforgeai.pojo.dto.ConfirmTokenRequestDTO dto) {
        return Result.success(secondaryConfirmService.issueConfirmToken(dto.getPassword()));
    }

    /**
     * 管理员修改自身密码。
     * <p>
     * 需要携带有效 accessToken，受防爆破锁定保护。
     */
    @Operation(summary = "管理员修改自身密码")
    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto, HttpServletRequest request) {
        String clientIp = ipResolver.resolve(request);
        userProfileService.changePassword(dto, clientIp);
        return Result.success();
    }
}
