package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 刷新令牌响应：只含令牌，不含用户信息
 *
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "刷新令牌响应")
@Data
public class TokenVO {

    @Schema(description = "新的访问令牌")
    private String accessToken;

    @Schema(description = "新的刷新令牌（旧的同时作废）")
    private String refreshToken;

    @Schema(description = "accessToken 有效期（秒）")
    private long expiresIn;
}
