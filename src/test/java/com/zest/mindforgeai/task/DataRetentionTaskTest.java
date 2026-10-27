package com.zest.mindforgeai.task;

import com.zest.mindforgeai.support.AbstractApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按表 + 时间的归档任务。
 *
 * <p>★ 为什么要单独一个测试类：{@code runWith} 是包级可见的（生产配置里没有任何可清理的表，
 * 见 {@code DataRetentionTask.FORBIDDEN}），必须同包才能调到。
 *
 * @author crx
 * @since 2026-10-27
 */
class DataRetentionTaskTest extends AbstractApiTest {

    @Autowired
    private DataRetentionTask task;

    @Test
    @DisplayName("黑名单里的表一律拒绝：配了 user 也不删")
    void refusesForbiddenTable() {
        long id = 990001L;
        jdbc.update("INSERT INTO `user` (id, username, email, password_hash, status, deleted) "
                + "VALUES (?, ?, ?, ?, 1, 1)", id, "ghost_user", "ghost@test.local", "x");

        task.runWith(Map.of("user", 30));

        // ★ 删了就等于把用户名释放出去，别人能重新注册 —— 这行必须还在
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("按时间归档：过期的进归档表并物理删，保留期内的不动")
    void archivesAndDeletesExpiredRows() {
        String table = "tmp_retention_test";
        try {
            jdbc.execute("CREATE TABLE " + table + " ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " name VARCHAR(64) DEFAULT NULL,"
                    + " deleted TINYINT NOT NULL DEFAULT 0,"
                    + " updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            // 一条 40 天前删的（超过 30 天保留期）+ 一条今天删的
            jdbc.update("INSERT INTO " + table + " (id, name, deleted, updated_at) "
                    + "VALUES (?, ?, 1, DATE_SUB(NOW(), INTERVAL 40 DAY))", 990002L, "old");
            jdbc.update("INSERT INTO " + table + " (id, name, deleted, updated_at) "
                    + "VALUES (?, ?, 1, NOW())", 990003L, "recent");

            task.runWith(Map.of(table, 30));

            // 过期的：进了归档表，主表里没了
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + "_archive", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE id = 990002", Integer.class)).isZero();
            // 保留期内的：一动没动
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE id = 990003", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
            jdbc.execute("DROP TABLE IF EXISTS " + table + "_archive");
        }
    }
}
