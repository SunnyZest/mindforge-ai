package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.SysAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志项，字段对齐文档 §二十八。
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "审计日志项")
@Data
public class AuditLogVO {

    @Schema(description = "日志 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "操作人用户 id；未登录（如登录失败）时为 null")
    private String userId;

    @Schema(description = "操作动作，如 ADMIN_LOGIN")
    private String action;

    @Schema(description = "资源类型")
    private String resourceType;

    @Schema(description = "资源 id")
    private String resourceId;

    @Schema(description = "链路 id，可与日志文件关联")
    private String requestId;

    @Schema(description = "HTTP 方法")
    private String httpMethod;

    @Schema(description = "请求路径")
    private String requestUri;

    @Schema(description = "客户端 IP")
    private String ip;

    @Schema(description = "User-Agent")
    private String userAgent;

    @Schema(description = "结果：SUCCESS / FAIL")
    private String result;

    @Schema(description = "明细（JSON 字符串，前端自行解析）")
    private String detailJson;

    @Schema(description = "发生时间")
    private LocalDateTime createdAt;

    public static AuditLogVO of(SysAuditLog log) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(log.getId() == null ? null : String.valueOf(log.getId()));
        vo.setUserId(log.getUserId() == null ? null : String.valueOf(log.getUserId()));
        vo.setAction(log.getAction());
        vo.setResourceType(log.getResourceType());
        vo.setResourceId(log.getResourceId());
        vo.setRequestId(log.getRequestId());
        vo.setHttpMethod(log.getHttpMethod());
        vo.setRequestUri(log.getRequestUri());
        vo.setIp(log.getIpAddress());
        vo.setUserAgent(log.getUserAgent());
        vo.setResult(log.getResult());
        vo.setDetailJson(log.getDetailJson());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }
}
