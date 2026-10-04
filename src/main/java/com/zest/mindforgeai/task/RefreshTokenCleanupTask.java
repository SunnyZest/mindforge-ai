package com.zest.mindforgeai.task;

import com.zest.mindforgeai.service.RefreshTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 刷新令牌清理任务。
 * <p>
 * refresh token 有效期 14 天，过期后就是垃圾数据。不清理的话表会一直涨。
 * <p>
 * <b>为什么删除条件是「已过期」而不是「已撤销」</b>：
 * 已撤销但未过期的 token 必须保留 —— 它是<b>重放检测</b>的依据
 * （有人拿已撤销的 token 来刷新 = 该 token 泄露了）。
 * 等它自然过期后就彻底没用了，那时才能删。
 * <p>
 * <b>多实例注意</b>：将来部署多个实例时，每个实例都会执行这个任务。
 * 对「删除过期数据」这种幂等操作无害（删两次结果一样），只是白费 DB 压力；
 * 真到那一步再加分布式锁或选主。当前单实例，不用管。
 *
 * @author crx
 * @since 2026-10-04
 */
@Slf4j
@Component
public class RefreshTokenCleanupTask {

    /** 每批删除条数：避免一次删太多造成长事务 / 锁表 */
    private static final int BATCH_SIZE = 1000;

    /** 单次任务最多循环批次数，防止异常情况下无限循环 */
    private static final int MAX_BATCHES = 100;

    private final RefreshTokenService refreshTokenService;

    public RefreshTokenCleanupTask(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 每天凌晨 3:10 清理已过期的 refresh token（cron 可通过配置覆盖）。
     * <p>
     * 选凌晨是避开业务高峰。
     */
    @Scheduled(cron = "${mindforge.task.refresh-token-cleanup-cron:0 10 3 * * ?}")
    public void cleanupExpiredRefreshTokens() {
        try {
            int total = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int deleted = refreshTokenService.removeExpired(BATCH_SIZE);
                total += deleted;
                if (deleted < BATCH_SIZE) {
                    break;      // 最后一批不满，说明已删完
                }
            }

            if (total > 0) {
                log.info("清理过期 refreshToken 完成，共删除 {} 条", total);
            } else {
                log.debug("清理过期 refreshToken：无需清理");
            }
        } catch (Exception e) {
            // 兜底：任务失败不影响后续调度（Spring 本身也会捕获，显式记日志更清晰）
            log.error("清理过期 refreshToken 失败", e);
        }
    }
}
