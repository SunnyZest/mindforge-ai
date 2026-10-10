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
     * 用户名。
     * <p>
     * ⚠️ {@code @Size} 是<b>必须的</b>，不是为了好看 ——
     * 这个值会被拼进 Redis key（{@code mf:login:fail:u:{用户名}}），
     * 不限制长度的话 key 长度只受请求体大小约束。
     * 实测（2026-10-10）发一个 2000 字符的用户名，Redis 里就多了一个
     * <b>2016 字节</b>的 key、TTL 15 分钟；攻击者换个用户名就换一个 key，
     * 等于一个内存放大面。
     * <p>
     * 上限 32 对齐 {@code user.username varchar(32)}，也和 {@code RegisterDTO} 一致
     * （注册那边本来就卡了 3-32，只有登录漏了）。
     * <p>
     * ⚠️ 刻意<b>不加 {@code min}</b>：登录只需要「不超过列宽」这一条约束，
     * 加最小值反而会把将来可能出现的短用户名挡在门外。
     */
    @Schema(description = "用户名", example = "zhangsan")
    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名长度不能超过 32 个字符")
    private String username;

    @Schema(description = "密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
