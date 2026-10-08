package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码，文档 §5.4。
 * <p>
 * 要求提供原密码：仅凭一个有效的 accessToken 就能改密码的话，
 * token 一旦泄漏（XSS、日志、截图）攻击者就能直接接管账号。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "修改密码")
@Data
public class ChangePasswordDTO {

    @Schema(description = "原密码")
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @Schema(description = "新密码")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度需在 6-64 之间")
    private String newPassword;
}
