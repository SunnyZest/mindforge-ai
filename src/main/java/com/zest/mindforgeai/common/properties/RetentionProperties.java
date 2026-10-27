package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据保留策略，对应 application.yml 的 {@code mindforge.retention.*}。
 *
 * <p>★ <b>默认关闭，而且默认策略是空的</b>。这是一个「会物理删数据」的任务，
 * 必须由人显式配置才会跑 —— 不能靠默认值猜。
 *
 * <p>用途：清理那些<b>逻辑删了、但从来没进过回收站</b>的数据
 * （回收站覆盖的 8 种资源由 {@code RecycleBinCleanupTask} 管，这里不要重复配置）。
 *
 * <p>⚠️ 配置前先看 {@code DataRetentionTask.FORBIDDEN} ——
 * {@code user} / {@code sys_role} 这类表<b>永远不能加</b>：
 * 它们的用户名、角色 code 在逻辑删除后要永久占用，物理删了会被重新注册。
 *
 * <pre>
 * mindforge:
 *   retention:
 *     enabled: false          # 改 true 才会跑
 *     policies:
 *       some_table: 180       # 表名: 保留天数（deleted = 1 且 updated_at 早于 N 天前才处理）
 * </pre>
 *
 * @author crx
 * @since 2026-10-27
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.retention")
public class RetentionProperties {

    /** 是否启用。默认 false —— 破坏性任务必须显式开启 */
    private boolean enabled = false;

    /** 表名 → 保留天数。默认空，即什么都不清理 */
    private Map<String, Integer> policies = new LinkedHashMap<>();
}
