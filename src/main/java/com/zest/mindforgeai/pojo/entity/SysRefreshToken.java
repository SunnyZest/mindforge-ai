package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import io.swagger.v3.oas.annotations.media.Schema;
/**
 * <p>
 * 刷新令牌表
 * </p>
 *
 * @author crx
 * @since 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_refresh_token")
@Schema(description="SysRefreshToken对象,刷新令牌表")
public class SysRefreshToken implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "与用户表的id字段关联")
    private Long userId;

    private String tokenHash;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;

    /**
     * 会话 id：一次登录一个，refresh 轮转时继承。
     * <p>
     * ⚠️ 存量 token 这一列是 NULL（加字段之前签发的）。代码里遇到 NULL 会当成
     * 新会话重新生成，所以老用户刷新一次就补上了，不需要专门迁移。
     */
    @Schema(description = "会话 id：一次登录一个，refresh 轮转时继承")
    private Long sessionId;

    /** 登录端：APP 用户端 / ADMIN 管理端（见 SessionConstants） */
    @Schema(description = "登录端：APP / ADMIN")
    private String endType;

    @Schema(description = "登录时的客户端 IP")
    private String ip;

    /** UA 原文。展示时用 UserAgentParser 粗解析成「Chrome / Windows」 */
    @Schema(description = "User-Agent 原文")
    private String userAgent;

    @Schema(description = "最近活跃时间，每次刷新令牌时更新")
    private LocalDateTime lastActiveAt;

    /**
     * 作废原因，取值见 {@code SessionConstants.RevokeReason}。
     * <p>
     * 决定 refresh() 遇到「已撤销的 token」时怎么处理：只有 ROTATED 才算泄露
     * （撤销该用户全部会话），其余原因只是拒绝这一次。
     * <p>
     * 老数据这一列是 NULL —— 按 ROTATED 处理，保持加字段之前的行为。
     */
    @Schema(description = "作废原因：ROTATED / LOGOUT / KICKED / REVOKE_OTHERS / REVOKE_ALL")
    private String revokedReason;

}
