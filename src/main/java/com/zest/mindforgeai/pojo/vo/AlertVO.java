package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.SysAlert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 高危操作告警项。id / userId 用字符串承载，避免 JS 大整数精度丢失（对齐审计日志 VO）。
 *
 * @author crx
 * @since 2026-10-11
 */
@Schema(description = "高危操作告警项")
@Data
public class AlertVO {

    @Schema(description = "告警 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "触发操作的用户 id；系统触发时为 null")
    private String userId;

    @Schema(description = "高危操作，如 ROLE_ASSIGN_PERMISSION")
    private String action;

    @Schema(description = "资源类型")
    private String resourceType;

    @Schema(description = "资源 id")
    private String resourceId;

    @Schema(description = "触发请求的 traceId")
    private String requestId;

    @Schema(description = "告警标题")
    private String title;

    @Schema(description = "操作明细（JSON 字符串，前端自行解析）")
    private String detailJson;

    @Schema(description = "是否已读")
    private Boolean read;

    @Schema(description = "标记已读的管理员 id")
    private String readBy;

    @Schema(description = "标记已读时间")
    private LocalDateTime readAt;

    @Schema(description = "发生时间")
    private LocalDateTime createdAt;

    public static AlertVO of(SysAlert alert) {
        AlertVO vo = new AlertVO();
        vo.setId(alert.getId() == null ? null : String.valueOf(alert.getId()));
        vo.setUserId(alert.getUserId() == null ? null : String.valueOf(alert.getUserId()));
        vo.setAction(alert.getAction());
        vo.setResourceType(alert.getResourceType());
        vo.setResourceId(alert.getResourceId());
        vo.setRequestId(alert.getRequestId());
        vo.setTitle(alert.getTitle());
        vo.setDetailJson(alert.getDetailJson());
        vo.setRead(alert.getReadAt() != null);
        vo.setReadBy(alert.getReadBy() == null ? null : String.valueOf(alert.getReadBy()));
        vo.setReadAt(alert.getReadAt());
        vo.setCreatedAt(alert.getCreatedAt());
        return vo;
    }
}
