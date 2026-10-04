package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌 / 登出请求
 *
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "刷新令牌请求")
@Data
public class RefreshTokenDTO {

    @Schema(description = "刷新令牌（登录时返回）")
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
