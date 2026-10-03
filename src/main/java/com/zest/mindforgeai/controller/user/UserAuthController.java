package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.LoginDTO;
import com.zest.mindforgeai.pojo.dto.RegisterDTO;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.pojo.vo.LoginVO;
import com.zest.mindforgeai.pojo.vo.UserInfoVO;
import com.zest.mindforgeai.service.UserService;
import com.zest.mindforgeai.utils.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端认证。
 * <p>
 * 注意：这里不写 /api，由 {@code server.servlet.context-path=/api} 统一提供，
 * 对外路径为 {@code /api/app/auth/**}（文档 §4.1、§十）。
 *
 * @author crx
 * @since 2026-10-03
 */
@Tag(name = "用户端-认证")
@SecurityRequirements   // 公开接口：注册 / 登录无需认证，摘掉文档上的锁标记
@RestController
@RequestMapping("/app/auth")
public class UserAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public UserAuthController(AuthenticationManager authenticationManager,
                              JwtUtil jwtUtil,
                              UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<UserInfoVO> register(@Valid @RequestBody RegisterDTO dto) {
        User user = userService.register(dto.getUsername(), dto.getEmail(), dto.getPassword());
        List<String> roles = userService.listRoleCodes(user.getId());
        return Result.success(UserInfoVO.of(user, roles, List.of()));
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        // 查库 + BCrypt 校验，失败抛 BadCredentialsException，由全局异常处理器转 40101
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));

        User user = userService.getByUsername(auth.getName());
        List<String> roles = userService.listRoleCodes(user.getId());

        LoginVO vo = new LoginVO();
        vo.setAccessToken(jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles));
        vo.setRefreshToken(null);                       // 阶段 3 实现
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        vo.setUser(UserInfoVO.of(user, roles, List.of()));   // permissions 待 RBAC 查询实现后填充
        return Result.success(vo);
    }
}
