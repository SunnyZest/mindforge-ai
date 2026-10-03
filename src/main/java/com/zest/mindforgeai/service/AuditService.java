package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.SysAuditLog;

/**
 * 审计日志服务。
 * <p>
 * 与业务日志（logback 输出的 logs/*.log）的分工：
 * 日志文件给开发排障用、会被轮转删除；审计落库、长期保留，供管理端 {@code admin:audit:list} 查询。
 *
 * @author crx
 * @since 2026-10-03
 */
public interface AuditService extends IService<SysAuditLog> {

    /**
     * 记录一条审计日志。
     * <p>
     * 调用方只需关心「谁对什么做了什么」，以下信息会自动补全：
     * <ul>
     *   <li>操作人 userId —— 取自 SecurityContext，未登录时为 null（如登录失败）</li>
     *   <li>requestId —— 取自 MDC，即当前请求的 traceId</li>
     *   <li>httpMethod / requestUri / ipAddress / userAgent —— 取自当前 HTTP 请求</li>
     * </ul>
     *
     * @param action       操作动作，建议用 {@code AuditConstants} 里的常量，如 USER_ASSIGN_ROLE
     * @param resourceType 资源类型，如 USER / ROLE，可空
     * @param resourceId   资源 id，可空
     * @param success      是否成功，写入 result 字段（SUCCESS / FAIL）
     * @param detail       详细信息，会序列化为 JSON 存入 detail_json，可空
     */
    void record(String action, String resourceType, String resourceId,
                boolean success, Object detail);

    /**
     * 记录一条审计日志，并<b>显式指定操作人</b>。
     * <p>
     * 用于拿不到 SecurityContext 的场景：定时任务、异步线程、消息消费者。
     * 有 HTTP 请求上下文的普通业务接口请用不传 userId 的重载，它会自动从 SecurityContext 取。
     *
     * @param userId 操作人用户 id，可传 null 表示「系统自动触发」
     */
    void record(Long userId, String action, String resourceType, String resourceId,
                boolean success, Object detail);

    /** 记录成功操作 */
    void recordSuccess(String action, String resourceType, String resourceId, Object detail);

    /** 记录失败操作（登录失败、越权尝试等） */
    void recordFailure(String action, String resourceType, String resourceId, Object detail);
}
