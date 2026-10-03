package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.LoginDTO;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.pojo.vo.LoginVO;
import com.zest.mindforgeai.pojo.vo.UserInfoVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.UserService;
import com.zest.mindforgeai.utils.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
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
@SecurityRequirements   // 公开接口：管理端登录无需认证，摘掉文档上的锁标记
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuditService auditService;

    public AdminAuthController(AuthenticationManager authenticationManager,
                               JwtUtil jwtUtil,
                               UserService userService,
                               AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.auditService = auditService;
    }

    @Operation(summary = "管理员登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
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
            // 登录失败也要留痕：此时尚未认证成功，SecurityContext 为空，userId 记 null，
            // 把「尝试的用户名」放进 detail，便于排查撞库 / 爆破
            auditService.recordFailure(AuditConstants.ACTION_ADMIN_LOGIN,
                    AuditConstants.RESOURCE_USER, null,
                    Map.of("attemptedUsername", dto.getUsername(),
                           "reason", e.getClass().getSimpleName()));
            throw e;    // 继续抛出，交给 GlobalExceptionHandler 转成 40101
        }

        // ★ 认证成功 → 写入 SecurityContext。
        //   authenticate() 只返回 Authentication，并不会设置 SecurityContext，
        //   这里补上之后，本次请求后续的逻辑（含审计取 userId）都能正常拿到当前用户。
        SecurityContextHolder.getContext().setAuthentication(auth);

        User user = userService.getByUsername(auth.getName());
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

        log.info("管理员登录成功 userId={} username={} roles={}",
                user.getId(), user.getUsername(), roles);

        LoginVO vo = new LoginVO();
        vo.setAccessToken(jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles));
        vo.setRefreshToken(null);                       // 阶段 3 实现
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        vo.setUser(UserInfoVO.of(user, roles, List.of()));
        return Result.success(vo);
    }
}
