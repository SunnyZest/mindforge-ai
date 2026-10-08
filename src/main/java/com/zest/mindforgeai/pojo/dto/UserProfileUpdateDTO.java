package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户修改自己的资料，文档 §十二。
 * <p>
 * 字段都可空：<b>传 null 表示不修改该字段</b>（局部更新）。
 * <p>
 * 只有昵称和头像 —— <b>刻意不含邮箱和手机号</b>：
 * 改联系方式需要先验证新值可用（发验证码 / 点确认链接），
 * 否则用户可以随手把邮箱改成别人的、或者打错一个字就永久占用掉一个邮箱
 * （本项目的邮箱是「一经使用即永久占用」的）。这个流程留到有邮件服务时再做。
 * <p>
 * 也不含用户名 —— 用户名是账号标识，同样受唯一索引的「永久占用」约束，不允许改。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "修改个人资料")
@Data
public class UserProfileUpdateDTO {

    @Schema(description = "昵称")
    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    @Schema(description = "头像地址")
    @Size(max = 500, message = "头像地址最长 500 个字符")
    private String avatarUrl;
}
