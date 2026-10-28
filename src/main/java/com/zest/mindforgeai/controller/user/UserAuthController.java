package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.ChangePasswordDTO;
import com.zest.mindforgeai.pojo.dto.LoginDTO;
import com.zest.mindforgeai.pojo.dto.RefreshTokenDTO;
import com.zest.mindforgeai.pojo.dto.RegisterDTO;
import com.zest.mindforgeai.pojo.dto.ResetPasswordDTO;
import com.zest.mindforgeai.pojo.dto.SendVerificationCodeDTO;
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
import com.zest.mindforgeai.service.VerificationCodeService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户端认证。
 * <p>
 * 注意：这里不写 /api，由 {@code server.servlet.context-path=/api} 统一提供，
 * 对外路径为 {@code /api/app/auth/**}（文档 §4.1、§十）。
 *
 * @author crx
 * @since 2026-10-03
 */
@Slf4j
@Tag(name = "用户端-认证")
@RestController
@RequestMapping("/app/auth")
public class UserAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final ClientIpResolver ipResolver;
    private final UserProfileService userProfileService;
    private final SecondaryConfirmService secondaryConfirmService;
    private final VerificationCodeService verificationCodeService;

    public UserAuthController(AuthenticationManager authenticationManager,
                              JwtUtil jwtUtil,
                              UserService userService,
                              AuditService auditService,
                              RefreshTokenService refreshTokenService,
                              LoginAttemptService loginAttemptService,
                              ClientIpResolver ipResolver,
                              UserProfileService userProfileService,
                              SecondaryConfirmService secondaryConfirmService,
                              VerificationCodeService verificationCodeService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.ipResolver = ipResolver;
        this.userProfileService = userProfileService;
        this.secondaryConfirmService = secondaryConfirmService;
        this.verificationCodeService = verificationCodeService;
    }

    @Operation(summary = "发送邮箱/短信验证码",
            description = "公开接口。受 60 秒冷却 + 每日上限约束；"
                    + "scene 决定邮件文案（REGISTER / RESET_PASSWORD）")
    @SecurityRequirements   // 公开接口：未登录的用户才需要发码
    @PostMapping("/verification-code")
    public Result<Void> sendVerificationCode(@Valid @RequestBody SendVerificationCodeDTO dto) {
        verificationCodeService.send(dto.getChannel(), dto.getTarget(), dto.getScene());
        // 刻意不回任何信息（不返回「该邮箱是否已注册」）—— 那等于送一个探测接口
        return Result.success();
    }

    @Operation(summary = "找回密码（邮箱验证码）",
            description = "未登录可用。邮箱没注册过也返回成功，避免被用来探测账号是否存在")
    @SecurityRequirements
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordDTO dto) {
        userService.resetPasswordByEmail(dto.getEmail(), dto.getCode(), dto.getNewPassword());
        return Result.success();
    }

    @Operation(summary = "用户注册")
    @SecurityRequirements   // 公开接口：注册无需认证，摘掉文档上的锁标记
    @PostMapping("/register")
    public Result<UserInfoVO> register(@Valid @RequestBody RegisterDTO dto) {
        User user = userService.register(
                dto.getUsername(), dto.getEmail(), dto.getCode(), dto.getPassword());
        List<String> roles = userService.listRoleCodes(user.getId());

        log.info("用户注册成功 userId={} username={} roles={}",
                user.getId(), user.getUsername(), roles);

        return Result.success(UserInfoVO.of(user, roles,
                userService.listPermissionCodes(user.getId())));
    }

    @Operation(summary = "用户登录")
    @SecurityRequirements   // 公开接口：登录无需认证，摘掉文档上的锁标记
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        String clientIp = ipResolver.resolve(request);

        // 先看有没有被锁 —— 锁定期内连密码都不用验，直接拒
        loginAttemptService.checkNotLocked(dto.getUsername(), clientIp);

        Authentication auth;
        try {
            // 查库 + BCrypt 校验，失败抛 BadCredentialsException，由全局异常处理器转 40101
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));
        } catch (AuthenticationException e) {
            // 记失败次数 —— 连续多次会锁定账号 / IP
            loginAttemptService.recordFailure(dto.getUsername(), clientIp);

            // 用户端只记「登录失败」：正常登录频率高，全记会让审计表被淹没；
            // 但失败值得留痕，用于发现撞库 / 爆破
            auditService.recordFailure(AuditConstants.ACTION_USER_LOGIN,
                    AuditConstants.RESOURCE_USER, null,
                    Map.of("attemptedIdentifier", dto.getUsername(),
                           "clientIp", clientIp,
                           "reason", e.getClass().getSimpleName()));
            throw e;    // 继续抛出，交给 GlobalExceptionHandler 转成 40101
        }

        // 登录成功，清掉该账号的失败计数
        loginAttemptService.recordSuccess(dto.getUsername(), clientIp);

        // ⚠️ 不能再用 getByUsername(auth.getName())：邮箱登录时 auth.getName() 是邮箱，
        //    按用户名查会返回 null。改用认证结果里的 userId —— 顺带也不受「用户名变更」影响。
        LoginUser principal = (LoginUser) auth.getPrincipal();
        User user = userService.getById(principal.getUserId());
        List<String> roles = userService.listRoleCodes(user.getId());
        List<String> permissions = userService.listPermissionCodes(user.getId());

        // ★ 同一个账号有两种标识（用户名 / 邮箱），失败计数也是分开记的 ——
        //   用邮箱登成功时要把用户名那份也清掉，否则会出现「邮箱能登、用户名还被锁着」。
        if (user.getEmail() != null) {
            loginAttemptService.recordSuccess(user.getEmail(), clientIp);
        }

        // 记录最后登录时间与 IP —— last_login_at 以前只有读没有写，管理端列表里永远是 null
        userService.touchLastLogin(user.getId(), clientIp);

        log.info("用户登录成功 userId={} username={} roles={} permissions={}",
                user.getId(), user.getUsername(), roles, permissions.size());

        LoginVO vo = new LoginVO();
        vo.setAccessToken(jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles));
        vo.setRefreshToken(refreshTokenService.issue(user.getId()));
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        vo.setUser(UserInfoVO.of(user, roles, permissions));
        return Result.success(vo);
    }

    /**
     * 获取当前登录用户信息。
     * <p>
     * 需要认证的接口：{@code /app/auth/me} 不在 SecurityConfig 的白名单里，
     * 必须携带有效 accessToken 才能访问，否则由 JwtAuthenticationEntryPoint 返回 401。
     * <p>
     * 前端用途：刷新页面时拉取最新的用户信息与权限，避免权限变更后必须重新登录。
     */
    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public Result<UserInfoVO> me() {
        // 能走到这里说明已通过认证，userId 直接从 SecurityContext 取（零查库）
        Long userId = SecurityUtils.getUserId();

        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        List<String> roles = userService.listRoleCodes(userId);
        List<String> permissions = userService.listPermissionCodes(userId);

        log.debug("查询当前登录用户信息 userId={} username={} roles={} permissions={}",
                userId, user.getUsername(), roles, permissions.size());

        return Result.success(UserInfoVO.of(user, roles, permissions));
    }

    /**
     * 刷新 access token。
     * <p>
     * 公开接口：此时 access token 早已过期，只能靠 body 里的 refreshToken 认证。
     * 采用<b>轮换</b>策略 —— 旧的 refreshToken 立即作废，返回一对全新令牌。
     * 如果传入的是<b>已撤销</b>的 token，会判定为泄露并撤销该用户全部 refreshToken。
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
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        List<String> roles = userService.listRoleCodes(result.userId());

        log.debug("刷新 access token userId={} username={}", result.userId(), user.getUsername());

        TokenVO vo = new TokenVO();
        vo.setAccessToken(jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles));
        vo.setRefreshToken(result.refreshToken());
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        return Result.success(vo);
    }

    /**
     * 登出。
     * <p>
     * 撤销 body 里的 refreshToken。<b>不依赖 access token</b> —— 因为它可能已过期，
     * 而"能拿到 refreshToken 的人本来就能换 access token"，所以这样不增加攻击面。
     * <p>
     * 注意：access token 是无状态的，<b>剩余有效期内仍然可用</b>（JWT 的固有取舍）。
     * 想彻底缩短这个窗口，就缩短 accessToken 的有效期。
     */
    @Operation(summary = "用户登出")
    @SecurityRequirements   // 公开接口：靠 body 里的 refreshToken 认证
    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody RefreshTokenDTO dto) {
        refreshTokenService.revoke(dto.getRefreshToken());
        log.info("用户登出");
        return Result.success();
    }

    /**
     * 修改当前用户密码。
     * <p>
     * 放在 /auth 下而不是 /user 下，是因为它属于「认证凭据」的变更，
     * 文档 §5.4 也把它归在认证一节。
     * <p>
     * 需要携带有效 accessToken（本接口不在白名单里），并额外提供原密码。
     */
    @Operation(summary = "修改当前用户密码")
    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto, HttpServletRequest request) {
        String clientIp = ipResolver.resolve(request);
        userProfileService.changePassword(dto, clientIp);
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
}
