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
 * <p>
 * 系统审计日志表
 * </p>
 *
 * @author crx
 * @since 2026-10-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_audit_log")
@Schema(description = "SysAuditLog对象,系统审计日志表")
public class SysAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "操作人用户 id，关联 user.id（无外键）；系统自动触发时为 null")
    private Long userId;

    @Schema(description = "链路追踪 id，对应请求头 X-Request-Id / 响应体 traceId")
    private String requestId;

    @Schema(description = "操作动作，如 ADMIN_LOGIN / USER_ASSIGN_ROLE")
    private String action;

    @Schema(description = "资源类型，如 USER / ROLE / AI_CONFIG")
    private String resourceType;

    @Schema(description = "资源 id，被操作对象的标识")
    private String resourceId;

    @Schema(description = "HTTP 方法，如 POST / PUT / DELETE")
    private String httpMethod;

    @Schema(description = "请求路径")
    private String requestUri;

    @Schema(description = "客户端 IP")
    private String ipAddress;

    @Schema(description = "客户端 User-Agent")
    private String userAgent;

    @Schema(description = "操作结果：SUCCESS / FAIL")
    private String result;

    @Schema(description = "详细信息（JSON），建议存新旧值对比")
    private String detailJson;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
