package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录响应，文档 §5.4
 *
 * @author crx
 * @since 2026-10-03
 */
@Schema(description = "登录响应")
@Data
public class LoginVO {

    @Schema(description = "访问令牌")
    private String accessToken;

    @Schema(description = "刷新令牌，阶段 3 实现")
    private String refreshToken;

    @Schema(description = "accessToken 有效期（秒）")
    private long expiresIn;

    @Schema(description = "用户信息")
    private UserInfoVO user;
}
