package com.zest.mindforgeai.controller.admin;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.LoginDTO;
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
 * 管理端认证，对外路径 {@code /api/admin/auth/**}（文档 §4.2、§十一）。
 *
 * @author crx
 * @since 2026-10-03
 */
@Tag(name = "管理端-认证")
@SecurityRequirements   // 公开接口：管理端登录无需认证，摘掉文档上的锁标记
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public AdminAuthController(AuthenticationManager authenticationManager,
                               JwtUtil jwtUtil,
                               UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Operation(summary = "管理员登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));

        User user = userService.getByUsername(auth.getName());
        List<String> roles = userService.listRoleCodes(user.getId());

        // 管理端入口必须校验角色，否则普通用户也能从这里拿到 token（文档 §7.3）
        boolean isAdmin = roles.stream().anyMatch(code ->
                RoleConstants.ADMIN.equals(code) || RoleConstants.SUPER_ADMIN.equals(code));
        if (!isAdmin) {
            throw new BizException(ErrorCode.FORBIDDEN, "该账号无管理端登录权限");
        }

        LoginVO vo = new LoginVO();
        vo.setAccessToken(jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roles));
        vo.setRefreshToken(null);                       // 阶段 3 实现
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        vo.setUser(UserInfoVO.of(user, roles, List.of()));
        return Result.success(vo);
    }
}
