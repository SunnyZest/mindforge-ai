package com.zest.mindforgeai.task;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 回收站过期清理。
 *
 * <p>★ <b>为什么需要它</b>：{@code recycle_bin} 原来只增不减 —— 只有「恢复」和「彻底删除」
 * 会删记录，过期的记录永远留着。表变大之后，{@code SysFileMapper.findProtectedFileIds()}
 * 那条 UNION JOIN 会明显变慢，进而拖慢文件清理任务。
 *
 * <p>★ <b>顺序不能反：先归档、再删业务行、最后删回收站记录</b>。
 * 如果只删回收站记录，业务表里 {@code deleted = 1} 的行就变成「永远查不到、也删不掉」的孤儿
 * —— 用户恢复不了（记录没了）、管理员也找不到（逻辑删）。
 *
 * <p>★ <b>归档只存轻量元数据，不存完整 payload</b>：回收站过期意味着用户已经放弃恢复权，
 * 归档的目的是留痕（谁、什么时候、删了什么），不是「为了将来还能还原」。
 * 关联数据（技能 / 题目 / 消息）在删除那一刻就已经物理删了，想还原也还原不了。
 *
 * @author crx
 * @since 2026-10-26
 */
@Slf4j
@Component
public class RecycleBinCleanupTask {

    /** 每批处理条数 */
    private static final int BATCH_SIZE = 200;

    /** 单次运行最多处理多少批 —— 防止某次积压太多把任务拖成一整夜 */
    private static final int MAX_BATCHES = 50;

    private final JdbcTemplate jdbc;

    @Value("${mindforge.recycle.cleanup-enabled:true}")
    private boolean cleanupEnabled;

    public RecycleBinCleanupTask(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${mindforge.recycle.cleanup-cron:0 50 3 * * ?}")
    public void cleanupExpired() {
        if (!cleanupEnabled) {
            return;
        }

        try {
            ensureArchiveTable();

            int totalArchived = 0;
            int totalRows = 0;

            for (int round = 0; round < MAX_BATCHES; round++) {
                // 一次取出整行 —— 后面归档和删业务行都要用，省得再查一遍
                List<Map<String, Object>> batch = jdbc.queryForList(
                        "SELECT id, user_id, resource_type, resource_id, title, payload_json, "
                                + "deleted_at, expire_at FROM recycle_bin "
                                + "WHERE expire_at < NOW() ORDER BY id ASC LIMIT ?", BATCH_SIZE);

                if (batch.isEmpty()) {
                    break;
                }

                for (Map<String, Object> row : batch) {
                    archive(row);
                    int purged = purgeBusinessRow(row);
                    // ⚠️ 必须放到最后：业务行没删掉就删记录，会留下孤儿数据
                    jdbc.update("DELETE FROM recycle_bin WHERE id = ?", row.get("id"));
                    totalRows++;
                    totalArchived += purged;
                }

                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }

            if (totalRows > 0) {
                log.info("回收站过期清理完成：归档并删除 {} 条记录，连带物理删除 {} 行业务数据",
                        totalRows, totalArchived);
            } else {
                log.debug("回收站过期清理：无需清理");
            }
        } catch (Exception e) {
            // 兜底：任务失败不影响后续调度
            log.error("回收站过期清理失败", e);
        }
    }

    /** 归档一行，留痕用 */
    private void archive(Map<String, Object> row) {
        jdbc.update("INSERT INTO deleted_data_archive "
                        + "(id, resource_type, original_id, user_id, title, payload_json, "
                        + "deleted_at, expire_at, archived_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                IdWorker.getId(),
                row.get("resource_type"),
                row.get("resource_id"),
                row.get("user_id"),
                row.get("title"),
                row.get("payload_json"),
                row.get("deleted_at"),
                row.get("expire_at"));
    }

    /**
     * 物理删业务表里那一行（只删 {@code deleted = 1} 的，防御性写法）。
     *
     * @return 实际删掉的主表行数（0 表示原行早就没了）
     */
    private int purgeBusinessRow(Map<String, Object> row) {
        RecycleResourceType type;
        try {
            type = RecycleResourceType.valueOf(String.valueOf(row.get("resource_type")));
        } catch (IllegalArgumentException e) {
            // 枚举里没有的类型（脏数据 / 老版本遗留）—— 只清回收站记录，不动业务表
            log.warn("回收站记录的资源类型无法识别，跳过业务表清理：type={}", row.get("resource_type"));
            return 0;
        }

        Object resourceId = row.get("resource_id");
        int deleted = jdbc.update(
                "DELETE FROM " + type.getTable() + " WHERE id = ? AND deleted = 1", resourceId);

        // 知识库要级联 —— 它删除时把 document / conversation 一起逻辑删了
        if (type == RecycleResourceType.KNOWLEDGE_BASE) {
            deleted += jdbc.update(
                    "DELETE FROM document WHERE knowledge_base_id = ? AND deleted = 1", resourceId);
            deleted += jdbc.update(
                    "DELETE FROM conversation WHERE knowledge_base_id = ? AND deleted = 1", resourceId);
        }

        // 说明：job_skill / interview_question / chat_message 等关联数据在「删除」那一刻
        // 就已经物理删了，这里不用管；document_chunk 是刻意保留的（靠 vector_purged 机制清）。
        return deleted;
    }

    /**
     * 兜底建表 —— 与 DataArchiveServiceImpl 的做法一致。
     *
     * <p><b>建表语句已经在 {@code mindforge-schema.sql} 里了</b>（第 33 张表），
     * 新建的库不需要这里兜底。留着的唯一理由：给「schema 版本比代码旧」的库
     * （比如线上还没跑最新建表脚本）兜一次底，避免清理任务因为缺表整个失败。
     *
     * <p>{@code IF NOT EXISTS} 是幂等的，正常情况下一句 DDL 都不会执行。
     * 建表失败只打 WARN 不抛异常 —— 归档表建不出来不该让回收站清理停摆。
     *
     * <p>⚠️ 改这张表的结构时，下面这段 DDL 和 {@code mindforge-schema.sql} 里的
     * {@code deleted_data_archive} 要一起改，否则新库（schema 建的）和老库（这里建的）会不一样。
     */
    private void ensureArchiveTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS deleted_data_archive ("
                    + "  id BIGINT NOT NULL COMMENT '雪花 id',"
                    + "  resource_type VARCHAR(32) NOT NULL COMMENT '资源类型（RecycleResourceType）',"
                    + "  original_id BIGINT NOT NULL COMMENT '原业务表 id',"
                    + "  user_id BIGINT NOT NULL COMMENT '原数据所有者',"
                    + "  title VARCHAR(255) DEFAULT NULL COMMENT '原数据标题，便于人工追查',"
                    + "  payload_json JSON DEFAULT NULL COMMENT '删除时的快照（可能为 null）',"
                    + "  deleted_at DATETIME DEFAULT NULL COMMENT '原来什么时候被删的',"
                    + "  expire_at DATETIME DEFAULT NULL COMMENT '恢复期何时结束',"
                    + "  archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',"
                    + "  PRIMARY KEY (id),"
                    + "  KEY idx_type_original (resource_type, original_id),"
                    + "  KEY idx_user (user_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过期删除数据归档（只留痕，不保证可还原）'");
        } catch (Exception e) {
            log.warn("创建归档表 deleted_data_archive 失败（不影响清理逻辑）：{}", e.getMessage());
        }
    }
}
