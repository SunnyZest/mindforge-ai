package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码请求（未登录）。
 * <p>
 * 用邮箱验证码而不是「原密码」—— 用户就是忘了密码才来的。
 *
 * @author crx
 * @since 2026-10-18
 */
@Schema(description = "找回密码请求")
@Data
public class ResetPasswordDTO {

    @Schema(description = "注册邮箱", example = "you@qq.com")
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "邮箱验证码（scene 用 RESET_PASSWORD）", example = "123456")
    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 8, message = "验证码格式不正确")
    private String code;

    @Schema(description = "新密码", example = "newpass123")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度需在 6-64 之间")
    private String newPassword;
}
