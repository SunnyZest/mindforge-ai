package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.zest.mindforgeai.support.AbstractApiTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据归档：把超过保留期的审计日志 / 已读告警搬进归档表。
 *
 * <p>这组用例是 2026-10-22 补的 —— 归档逻辑 2026-10-11 就写好了，但一直没有测试。
 * 补它的直接原因：查 dev 库时发现两张归档表压根不存在，一度误判成「任务会抛
 * Table doesn't exist」，翻代码才看到 {@code ensureArchiveTablesExist()} 会自建。
 * 也就是说「表存在」这件事是靠运行期兜底的，更该有用例把它钉住。
 *
 * <p>★ 两个容易写错的地方：
 * <ul>
 *   <li>告警只归档<b>已读</b>的（{@code read_at IS NOT NULL}）—— 未读的搬走了，
 *       管理端就再也看不到，等于悄悄吞掉一条待处理事项；</li>
 *   <li>边界是 {@code created_at < cutoff}，刚好等于保留期的不搬。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-22
 */
@DisplayName("数据归档")
class DataArchiveServiceTest extends AbstractApiTest {

    /** 造数据用的 action / 标题前缀，清理时按它删 */
    private static final String MARK = "TEST_ARCHIVE";

    @Autowired
    private DataArchiveService dataArchiveService;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM sys_audit_log WHERE action = ?", MARK);
        jdbc.update("DELETE FROM sys_audit_log_archive WHERE action = ?", MARK);
        jdbc.update("DELETE FROM sys_alert WHERE title LIKE ?", MARK + "%");
        jdbc.update("DELETE FROM sys_alert_archive WHERE title LIKE ?", MARK + "%");
    }

    private long insertAuditLog(int daysAgo) {
        long id = IdWorker.getId();
        jdbc.update(
                "INSERT INTO sys_audit_log (id, action, result, created_at) VALUES (?, ?, 'SUCCESS', ?)",
                id, MARK, LocalDateTime.now().minusDays(daysAgo));
        return id;
    }

    private long insertAlert(int daysAgo, boolean read) {
        long id = IdWorker.getId();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(daysAgo);
        jdbc.update(
                "INSERT INTO sys_alert (id, action, title, read_at, created_at) VALUES (?, ?, ?, ?, ?)",
                id, MARK, MARK + "_" + id, read ? createdAt.plusHours(1) : null, createdAt);
        return id;
    }

    /** 表名是本类里写死的常量，不存在注入问题 */
    private int countById(String table, long id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("超期审计日志被搬进归档表，未超期的留在原表")
    void archivesExpiredAuditLogs() {
        long expired = insertAuditLog(100);
        long fresh = insertAuditLog(10);

        int archived = dataArchiveService.archiveAuditLogs(90);

        assertThat(archived).isGreaterThanOrEqualTo(1);
        assertThat(countById("sys_audit_log", expired)).isZero();
        assertThat(countById("sys_audit_log_archive", expired)).isEqualTo(1);
        assertThat(countById("sys_audit_log", fresh)).isEqualTo(1);
        assertThat(countById("sys_audit_log_archive", fresh)).isZero();
    }

    @Test
    @DisplayName("没有超期数据时不动原表")
    void keepsFreshAuditLogs() {
        long fresh = insertAuditLog(10);

        dataArchiveService.archiveAuditLogs(90);

        assertThat(countById("sys_audit_log", fresh)).isEqualTo(1);
        assertThat(countById("sys_audit_log_archive", fresh)).isZero();
    }

    @Test
    @DisplayName("只归档超期的已读告警：未读的、未到期的都不动")
    void archivesOnlyReadExpiredAlerts() {
        long expiredRead = insertAlert(40, true);
        long expiredUnread = insertAlert(40, false);
        long freshRead = insertAlert(10, true);

        int archived = dataArchiveService.archiveAlerts(30);

        assertThat(archived).isGreaterThanOrEqualTo(1);
        assertThat(countById("sys_alert", expiredRead)).isZero();
        assertThat(countById("sys_alert_archive", expiredRead)).isEqualTo(1);
        assertThat(countById("sys_alert", expiredUnread)).isEqualTo(1);
        assertThat(countById("sys_alert", freshRead)).isEqualTo(1);
    }
}
