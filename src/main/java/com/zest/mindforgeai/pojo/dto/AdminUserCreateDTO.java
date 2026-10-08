package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理端创建用户，文档 §十三。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "管理端创建用户")
@Data
public class AdminUserCreateDTO {

    @Schema(description = "用户名", example = "zhangsan")
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
    private String username;

    @Schema(description = "邮箱", example = "test@example.com")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "初始密码")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
    private String password;

    @Schema(description = "昵称；不传则默认与用户名相同")
    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    @Schema(description = "角色 id 列表；不传或为空则只绑定默认角色 USER")
    private List<Long> roleIds;
}
