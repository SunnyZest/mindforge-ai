package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 高危操作告警。
 * <p>
 * 和审计日志的分工：审计回答「发生过什么」（只读、永久留痕），
 * 告警回答「有什么需要有人去看」（有已读状态，读完就可以归档）。
 * 一条告警对应一次高危审计 —— 触发点在审计写入处，取值完全对齐审计字段。
 *
 * @author crx
 * @since 2026-10-11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_alert")
@Schema(description = "高危操作告警")
public class SysAlert implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "触发操作的用户 id（无外键）；系统触发时为 null")
    private Long userId;

    @Schema(description = "触发告警的高危操作，取值对齐 sys_audit_log.action")
    private String action;

    @Schema(description = "资源类型，如 USER / ROLE")
    private String resourceType;

    @Schema(description = "资源 id")
    private String resourceId;

    @Schema(description = "触发请求的 traceId，用于回查审计与日志")
    private String requestId;

    @Schema(description = "告警标题（通知渠道里展示的摘要）")
    private String title;

    @Schema(description = "操作明细，与审计日志的 detail_json 相同")
    private String detailJson;

    @Schema(description = "标记已读的管理员 id；未读为 null")
    private Long readBy;

    @Schema(description = "标记已读时间；未读为 null")
    private LocalDateTime readAt;

    @Schema(description = "发生时间")
    private LocalDateTime createdAt;
}
