package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送验证码请求。
 * <p>
 * ⚠️ 这个接口<b>必须公开</b>（未登录的用户才需要发码），
 * 所以它是「邮件轰炸」的入口 —— 限流在服务端，不依赖前端。
 *
 * @author crx
 * @since 2026-10-18
 */
@Schema(description = "发送验证码请求")
@Data
public class SendVerificationCodeDTO {

    @Schema(description = "通道：EMAIL / SMS", example = "EMAIL")
    @NotBlank(message = "验证码通道不能为空")
    @Pattern(regexp = "EMAIL|SMS", message = "验证码通道只支持 EMAIL / SMS")
    private String channel;

    @Schema(description = "目标：邮箱或手机号", example = "you@qq.com")
    @NotBlank(message = "接收地址不能为空")
    @Size(max = 64, message = "接收地址最长 64 个字符")
    private String target;

    @Schema(description = "场景：REGISTER / RESET_PASSWORD", example = "REGISTER")
    @NotBlank(message = "场景不能为空")
    @Pattern(regexp = "REGISTER|RESET_PASSWORD", message = "场景只支持 REGISTER / RESET_PASSWORD")
    private String scene;
}
