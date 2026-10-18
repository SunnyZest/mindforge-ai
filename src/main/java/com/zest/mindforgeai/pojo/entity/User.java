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

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;

    @Schema(description = "账号是否已删除(逻辑)")
    @TableLogic
    private Integer deleted;


}
