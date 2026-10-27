package com.zest.mindforgeai.task;

import com.zest.mindforgeai.common.properties.RetentionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 按「表 + 时间」归档清理逻辑删除的数据。
 *
 * <p>★ <b>它解决什么问题</b>：有些表的数据逻辑删了、但从来没进过回收站
 * （回收站只覆盖 8 种用户可见资源）。这些行永远查不到、也没人清，一直占着磁盘。
 *
 * <p>★ <b>与 RecycleBinCleanupTask 的分工</b>：那边管「回收站里过期的」，
 * 这边管「压根没进回收站的」。两边处理的表不能重叠 —— 重叠会打架
 * （一个任务刚归档完，另一个又去删），所以 {@link #FORBIDDEN} 把回收站覆盖的表全挡掉了。
 *
 * <p>★ <b>为什么默认关闭、默认策略为空</b>：这是会物理删数据的任务。
 * 靠默认值「猜」出该删什么太危险，必须由人显式配置。
 *
 * <p>★ <b>归档表用 {@code {table}_archive}（{@code LIKE} 原表）</b>，
 * 存的是<b>完整行</b>，真出事还能手工捞回来。这跟 {@code deleted_data_archive}
 * （只存元数据、纯留痕）不是一回事。
 *
 * @author crx
 * @since 2026-10-27
 */
@Slf4j
@Component
public class DataRetentionTask {

    /** 每批处理条数 */
    private static final int BATCH_SIZE = 200;

    /** 单表单次最多处理多少批 */
    private static final int MAX_BATCHES = 50;

    /**
     * 永远不能归档清理的表 —— 不是「暂时不加」，是「加了会出事故」。
     *
     * <p>每条都写清原因，避免以后有人觉得「这不就是张普通表吗」就加进配置里。
     */
    private static final Map<String, String> FORBIDDEN = Map.ofEntries(
            Map.entry("user",
                    "用户名 / 邮箱逻辑删除后要永久占用，物理删了会被别人重新注册"),
            Map.entry("sys_role",
                    "角色 code 逻辑删除后永久占用，且被 sys_user_role 引用"),
            Map.entry("sys_file",
                    "由 FilePurgeTask 专门管理：要删 OSS 对象，还要保护回收站里未过期的文件"),
            // ---- 以下由 RecycleBinCleanupTask 管，配置了会双任务打架 ----
            Map.entry("job_offer", "由回收站清理任务管理"),
            Map.entry("job_event", "由回收站清理任务管理"),
            Map.entry("job", "由回收站清理任务管理"),
            Map.entry("interview", "由回收站清理任务管理"),
            Map.entry("conversation", "由回收站清理任务管理"),
            Map.entry("resume", "由回收站清理任务管理"),
            Map.entry("knowledge_base", "由回收站清理任务管理"),
            Map.entry("document", "由回收站清理任务管理"));

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;

    public DataRetentionTask(JdbcTemplate jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "${mindforge.retention.cron:0 10 4 * * ?}")
    public void archiveExpiredRows() {
        if (!properties.isEnabled()) {
            return;
        }
        Map<String, Integer> policies = properties.getPolicies();
        if (policies == null || policies.isEmpty()) {
            log.debug("数据保留策略为空，跳过");
            return;
        }
        runWith(policies);
    }

    /**
     * 按给定策略执行 —— 包级可见，方便测试传临时策略验证执行路径
     * （生产配置里没有任何可清理的表，见 {@link #FORBIDDEN}）。
     */
    void runWith(Map<String, Integer> policies) {
        int totalArchived = 0;
        int tablesHandled = 0;

        for (Map.Entry<String, Integer> entry : policies.entrySet()) {
            String table = entry.getKey();
            Integer days = entry.getValue();

            String reason = FORBIDDEN.get(table);
            if (reason != null) {
                log.warn("拒绝清理表 {}：{}", table, reason);
                continue;
            }
            if (days == null || days <= 0) {
                log.warn("表 {} 的保留天数非法（{}），跳过", table, days);
                continue;
            }

            int archived = archiveTable(table, days);
            if (archived > 0) {
                tablesHandled++;
                totalArchived += archived;
            }
        }

        if (totalArchived > 0) {
            log.info("数据保留清理完成：{} 张表共归档并删除 {} 行", tablesHandled, totalArchived);
        } else {
            log.debug("数据保留清理：没有需要处理的数据");
        }
    }

    private int archiveTable(String table, int days) {
        String archive = table + "_archive";

        // 归档表结构与源表一致（LIKE 保证列名、类型、顺序完全一样，
        // 后面 INSERT ... SELECT * 才不会错位）
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + archive + " LIKE " + table);
        } catch (Exception e) {
            log.warn("创建归档表 {} 失败，跳过该表：{}", archive, e.getMessage());
            return 0;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int archived = 0;

        for (int round = 0; round < MAX_BATCHES; round++) {
            List<Long> ids;
            try {
                ids = jdbc.queryForList(
                        "SELECT id FROM " + table
                                + " WHERE deleted = 1 AND updated_at < ? ORDER BY id ASC LIMIT ?",
                        Long.class, cutoff, BATCH_SIZE);
            } catch (Exception e) {
                // 表没有 deleted / updated_at 列 —— 配错了，跳过而不是让整个任务挂掉
                log.warn("表 {} 不支持按时间归档（缺 deleted 或 updated_at 列）：{}",
                        table, e.getMessage());
                return archived;
            }

            if (ids.isEmpty()) {
                break;
            }

            String placeholders = placeholders(ids.size());
            try {
                // ★ 先清归档表里的同 id 行，让整步操作幂等：
                //   万一上次「插完归档、还没删主表」就失败了，重跑不会在归档表里留两份
                jdbc.update("DELETE FROM " + archive + " WHERE id IN (" + placeholders + ")",
                        ids.toArray());
                // 归档完整行
                jdbc.update("INSERT INTO " + archive + " SELECT * FROM " + table
                        + " WHERE id IN (" + placeholders + ")", ids.toArray());
                // ★ 最后才删主表 —— 顺序反了会丢数据
                jdbc.update("DELETE FROM " + table + " WHERE id IN (" + placeholders + ")",
                        ids.toArray());
            } catch (Exception e) {
                // 最常见原因：源表后来加过列，归档表还是旧的 → INSERT ... SELECT * 列数不匹配。
                // 需要手工 ALTER TABLE xxx_archive ADD COLUMN ...，这里只跳过该表。
                log.error("表 {} 归档失败（若是列不一致，请手工 ALTER TABLE {} 补列）：{}",
                        table, archive, e.getMessage());
                return archived;
            }

            archived += ids.size();

            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }

        if (archived > 0) {
            log.info("表 {} 归档 {} 行（保留 {} 天，归档到 {}）", table, archived, days, archive);
        }
        return archived;
    }

    private String placeholders(int n) {
        return IntStream.range(0, n).mapToObj(i -> "?").collect(Collectors.joining(","));
    }
}
