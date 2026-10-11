package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.service.DataArchiveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 历史数据归档服务实现。
 * <p>
 * 采用分批拉取 ID、迁移至归档表再删除主表记录的方式，
 * 避免大事务造成长锁表，保障主表读写性能。
 *
 * @author crx
 * @since 2026-10-11
 */
@Slf4j
@Service
public class DataArchiveServiceImpl implements DataArchiveService {

    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;

    public DataArchiveServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int archiveAuditLogs(int retentionDays) {
        ensureArchiveTablesExist();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));

        int totalArchived = 0;
        while (true) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM sys_audit_log WHERE created_at < ? ORDER BY id ASC LIMIT ?",
                    Long.class, cutoff, BATCH_SIZE);

            if (ids.isEmpty()) {
                break;
            }

            archiveAuditLogBatch(ids);
            totalArchived += ids.size();

            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalArchived > 0) {
            log.info("审计日志归档完成：已将 {} 条超过 {} 天的历史日志迁入 sys_audit_log_archive",
                    totalArchived, retentionDays);
        }
        return totalArchived;
    }

    @Override
    public int archiveAlerts(int retentionDays) {
        ensureArchiveTablesExist();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));

        int totalArchived = 0;
        while (true) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM sys_alert WHERE read_at IS NOT NULL AND created_at < ? ORDER BY id ASC LIMIT ?",
                    Long.class, cutoff, BATCH_SIZE);

            if (ids.isEmpty()) {
                break;
            }

            archiveAlertBatch(ids);
            totalArchived += ids.size();

            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalArchived > 0) {
            log.info("告警归档完成：已将 {} 条超过 {} 天的已读告警迁入 sys_alert_archive",
                    totalArchived, retentionDays);
        }
        return totalArchived;
    }

    @Transactional(rollbackFor = Exception.class)
    protected void archiveAuditLogBatch(List<Long> ids) {
        String idList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbcTemplate.update("INSERT INTO sys_audit_log_archive SELECT * FROM sys_audit_log WHERE id IN (" + idList + ")");
        jdbcTemplate.update("DELETE FROM sys_audit_log WHERE id IN (" + idList + ")");
    }

    @Transactional(rollbackFor = Exception.class)
    protected void archiveAlertBatch(List<Long> ids) {
        String idList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbcTemplate.update("INSERT INTO sys_alert_archive SELECT * FROM sys_alert WHERE id IN (" + idList + ")");
        jdbcTemplate.update("DELETE FROM sys_alert WHERE id IN (" + idList + ")");
    }

    /** 确保归档表存在（幂等） */
    private void ensureArchiveTablesExist() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_audit_log_archive LIKE sys_audit_log");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_alert_archive LIKE sys_alert");
        } catch (Exception e) {
            log.warn("检查或创建归档表失败（如已存在请忽略）：{}", e.getMessage());
        }
    }
}
