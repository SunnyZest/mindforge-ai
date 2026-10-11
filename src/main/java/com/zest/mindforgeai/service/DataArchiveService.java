package com.zest.mindforgeai.service;

/**
 * 数据归档服务：冷热数据分离与历史数据归档。
 *
 * @author crx
 * @since 2026-10-11
 */
public interface DataArchiveService {

    /**
     * 归档历史审计日志。
     *
     * @param retentionDays 保留天数（例如 90 天），早于该天数的记录迁移至 sys_audit_log_archive
     * @return 归档总记录数
     */
    int archiveAuditLogs(int retentionDays);

    /**
     * 归档已读历史告警。
     *
     * @param retentionDays 保留天数（例如 30 天），早于该天数且已读的告警迁移至 sys_alert_archive
     * @return 归档总记录数
     */
    int archiveAlerts(int retentionDays);
}
