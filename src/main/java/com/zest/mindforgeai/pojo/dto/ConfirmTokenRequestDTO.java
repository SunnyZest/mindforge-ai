package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 获取高危操作二次确认凭证的请求体。
 *
 * @author crx
 * @since 2026-10-11
 */
@Data
@Schema(description = "二次确认凭证请求")
public class ConfirmTokenRequestDTO {

    @NotBlank(message = "当前登录密码不能为空")
    @Schema(description = "当前登录用户的密码")
    private String password;
}
