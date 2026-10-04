package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端修改用户资料。
 * <p>
 * 所有字段都可空：<b>传 null 表示不修改该字段</b>（局部更新）。
 *
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "管理端修改用户资料")
@Data
public class UserUpdateDTO {

    @Schema(description = "昵称")
    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    @Schema(description = "邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;

    @Schema(description = "手机号")
    @Size(max = 16, message = "手机号最长 16 个字符")
    private String phone;

    @Schema(description = "头像地址")
    @Size(max = 500, message = "头像地址最长 500 个字符")
    private String avatarUrl;
}
