package com.zest.mindforgeai.task;

import com.zest.mindforgeai.service.DataArchiveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 历史数据定时归档任务。
 * <p>
 * 每天凌晨 03:40 执行（避开 03:10 的 token 清理和 03:20 的文件清理），
 * 将 90 天前的审计日志和 30 天前的已读告警归档到历史表，防止主表数据量膨胀。
 *
 * @author crx
 * @since 2026-10-11
 */
@Slf4j
@Component
public class DataArchiveTask {

    private final DataArchiveService dataArchiveService;

    public DataArchiveTask(DataArchiveService dataArchiveService) {
        this.dataArchiveService = dataArchiveService;
    }

    @Scheduled(cron = "${mindforge.task.data-archive-cron:0 40 3 * * ?}")
    public void archiveHistoricalData() {
        try {
            log.info("开始执行历史数据归档任务...");
            int archivedLogs = dataArchiveService.archiveAuditLogs(90);
            int archivedAlerts = dataArchiveService.archiveAlerts(30);
            log.info("历史数据归档任务执行完毕：审计日志 {} 条，告警记录 {} 条",
                    archivedLogs, archivedAlerts);
        } catch (Exception e) {
            log.error("历史数据归档任务异常", e);
        }
    }
}
