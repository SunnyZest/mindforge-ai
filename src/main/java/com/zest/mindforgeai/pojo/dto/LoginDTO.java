package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求
 *
 * @author crx
 * @since 2026-10-03
 */
@Schema(description = "登录请求")
@Data
public class LoginDTO {

    /**
     * 登录标识：<b>用户名或邮箱</b>（后端自动识别，见 {@code CustomUserDetailsService}）。
     * <p>
     * ⚠️ {@code @Size} 是<b>必须的</b>，不是为了好看 ——
     * 这个值会被拼进 Redis key（{@code mf:login:fail:u:{标识}}），
     * 不限制长度的话 key 长度只受请求体大小约束。
     * 实测（2026-10-10）发一个 2000 字符的用户名，Redis 里就多了一个
     * <b>2016 字节</b>的 key、TTL 15 分钟；攻击者换个用户名就换一个 key，
     * 等于一个内存放大面。
     * <p>
     * 上限取 <b>64</b>：原来 32 是按 {@code user.username varchar(32)} 定的，
     * 现在这个字段也要能装下邮箱（{@code user.email varchar(64)}）。
     * 放宽不会放大上面那个问题 —— {@code LoginAttemptServiceImpl.normalize()}
     * 仍会截断到 32 再拼 key。
     * <p>
     * ⚠️ 刻意<b>不加 {@code min}</b>：登录只需要「不超过列宽」这一条约束，
     * 加最小值反而会把将来可能出现的短用户名挡在门外。
     */
    @Schema(description = "用户名或邮箱", example = "zhangsan")
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "登录标识长度不能超过 64 个字符")
    private String username;

    @Schema(description = "密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过 64 个字符")
    private String password;
}
