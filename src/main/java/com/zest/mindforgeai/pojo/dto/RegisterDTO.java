package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求，文档 §十
 * <p>
 * ★ 2026-10-18 起<b>邮箱与邮箱验证码都是必填</b>：注册必须先证明「你拥有这个邮箱」，
 * 否则任何人都能用别人的邮箱注册（后面找回密码会互相踩）。
 * 这是一次<b>破坏性变更</b>，前端注册表单要一起改。
 *
 * @author crx
 * @since 2026-10-03
 */
@Schema(description = "注册请求")
@Data
public class RegisterDTO {

    @Schema(description = "用户名", example = "zhangsan")
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
    private String username;

    @Schema(description = "邮箱", example = "test@example.com")
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "邮箱验证码（先调 /app/auth/verification-code 获取）", example = "123456")
    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 8, message = "验证码格式不正确")
    private String code;

    @Schema(description = "密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
    private String password;
}
