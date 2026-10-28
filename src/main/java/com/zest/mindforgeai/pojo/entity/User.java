package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 用户表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user")
@Schema(description="User对象,用户表")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户id")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "用户邮箱")
    private String email;

    /**
     * 邮箱是否已验证：0 未验证 / 1 已验证。
     * <p>
     * ★ 验证码只证明「此刻你拥有这个邮箱」，而「已验证」是长期状态 ——
     * 不落这个标记的话，每次登录、每次找回密码都得重新发码。
     * <p>
     * 现在注册时必然过验证码，所以新账号都是 1；历史账号是 0，
     * 下次改邮箱（走验证流程）时才会变成 1。
     */
    @Schema(description = "邮箱是否已验证：0 未验证 / 1 已验证")
    private Integer emailVerified;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "加密后密码")
    private String passwordHash;

    @Schema(description = "用户昵称")
    private String nickname;

    @Schema(description = "头像url")
    private String avatarUrl;

    @Schema(description = "1正常 0禁用")
    private Integer status;

    @Schema(description = "最后登陆时间")
    private LocalDateTime lastLoginAt;

    /**
     * 最后登录 IP（只记成功登录那一次的）。
     * <p>
     * 长度 64 与 {@code sys_audit_log.ip_address} 保持一致：IPv6 最长 45 字符，
     * 留出余量给「X-Forwarded-For 里带端口」这类脏值 —— 与其截断出错，不如存下来能看。
     * <p>
     * ⚠️ 这是<b>排障线索</b>，不是安全凭证：客户端可以伪造来源（未开
     * {@code trust-forwarded-headers} 时取的是 TCP 来源地址，伪造不了；
     * 开了之后依赖前面的代理如实覆盖写入）。判定「异地登录」只能当参考。
     */
    @Schema(description = "最后登录 IP")
    private String lastLoginIp;

    /**
     * 是否接收日程邮件提醒，默认开启。
     * <p>
     * ★ 默认 {@code 1} 而不是 {@code 0}：这是「本该有」的功能，不是营销邮件，
     * 让用户主动去开等于大多数人永远收不到。但必须给出口 ——
     * 没有退订开关的定时邮件，等于主动给用户一个投诉理由。
     */
    @Schema(description = "是否接收日程邮件提醒：0关闭 1开启")
    private Integer reminderEnabled;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;

    @Schema(description = "账号是否已删除(逻辑)")
    @TableLogic
    private Integer deleted;


}
