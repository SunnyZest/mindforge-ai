package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条登录会话（「我的登录设备」列表里的一行）。
 * <p>
 * 一个 sessionId 对应一次登录。同一次登录在 refresh 轮转过程中会产生多条
 * token 记录，但这里只会出现一行 —— 聚合在 service 里做。
 *
 * @author crx
 * @since 2026-10-30
 */
@Data
@Schema(description = "登录会话")
public class SessionVO {

    /**
     * 会话 id。
     * <p>
     * ⚠️ 用 String 而不是 Long：雪花 id 是 64 位，超过 JS 的
     * {@code Number.MAX_SAFE_INTEGER}，直接传数字前端会末位漂移 ——
     * 漂了之后就踢不掉这条会话（拿错 id）。项目里所有 VO 的 id 都是 String，保持一致。
     */
    @Schema(description = "会话 id（String 形式的雪花 id）")
    private String sessionId;

    @Schema(description = "设备描述，如「Chrome · Windows」")
    private String deviceName;

    @Schema(description = "登录时的客户端 IP")
    private String ip;

    @Schema(description = "登录端：APP 用户端 / ADMIN 管理端")
    private String endType;

    @Schema(description = "登录时间")
    private LocalDateTime createdAt;

    @Schema(description = "最近活跃时间（每次刷新令牌时更新）")
    private LocalDateTime lastActiveAt;

    /**
     * 是不是当前正在发起请求的这个会话。
     * <p>
     * 前端据此把这一行标成「本机」，并且**不允许踢自己** ——
     * 踢自己等于把自己登出，那应该走「登出」按钮。
     */
    @Schema(description = "是否为当前会话")
    private boolean current;
}
