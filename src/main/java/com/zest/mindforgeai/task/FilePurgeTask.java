package com.zest.mindforgeai.task;

import com.zest.mindforgeai.common.properties.StorageProperties;
import com.zest.mindforgeai.mapper.SysFileMapper;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 已删除文件的物理清理任务。
 * <p>
 * <b>为什么需要它</b>：用户删除文件时只做逻辑删除，OSS 对象保留 —— 这样误删还能救。
 * 但存储不能一直涨，所以需要这个任务在<b>恢复窗口</b>（默认 30 天）过后，
 * 把真正没人用的对象删掉。
 * <p>
 * <b>引用计数</b>：秒传会让多条 {@code sys_file} 记录共享同一个 object_key。
 * 只有当某个 object_key 的<b>所有</b>记录都过期了，才能删对象 ——
 * 否则还在恢复窗口内的记录被恢复后会指向一个不存在的文件。
 * 这个判断写在 {@code selectPurgeable} 的 NOT EXISTS 里。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Component
public class FilePurgeTask {

    /** 每批处理条数 */
    private static final int BATCH_SIZE = 200;

    /** 单次任务最多循环批次数，防止异常情况下无限循环 */
    private static final int MAX_BATCHES = 50;

    private final SysFileMapper sysFileMapper;
    private final FileStorageService storageService;
    private final StorageProperties storageProperties;

    public FilePurgeTask(SysFileMapper sysFileMapper,
                         FileStorageService storageService,
                         StorageProperties storageProperties) {
        this.sysFileMapper = sysFileMapper;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
    }

    /**
     * 每天 03:20 清理超过恢复窗口的已删除文件（cron 可配）。
     * <p>
     * 注意：多实例部署时每个实例都会跑。删除是幂等的（对象删两次结果一样），
     * 只是白费一点 IO；真到那一步再加分布式锁。
     */
    @Scheduled(cron = "${mindforge.storage.purge-cron:0 20 3 * * ?}")
    public void purgeExpiredFiles() {
        int retentionDays = storageProperties.getRetentionDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        try {
            int totalPurged = 0;

            // ★ 回收站里还有未过期记录的文件不能删 —— 用户随时可能恢复，
            //   删了 OSS 对象就等于「记录能恢复、文件下载不了」。
            //   恢复窗口和保留期都是 30 天但会错位（第 29 天恢复、第 30 天被清）。
            Set<Long> protectedIds = new HashSet<>(sysFileMapper.findProtectedFileIds());
            int totalProtected = 0;

            for (int i = 0; i < MAX_BATCHES; i++) {
                List<SysFile> batch = sysFileMapper.selectPurgeable(cutoff, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }

                int purgedThisBatch = 0;
                for (SysFile file : batch) {
                    if (protectedIds.contains(file.getId())) {
                        // ⚠️ 不能只是 continue：那样它每次都会被查出来、占满批次，
                        //    把后面真正该清理的记录堵在外面（批次数有上限）。
                        //    把 deleted_at 推到 now（续期），它要再过 retentionDays 天才到期，
                        //    而那时回收站记录也早过期了。
                        sysFileMapper.touchDeletedAt(file.getId());
                        totalProtected++;
                        continue;
                    }
                    storageService.delete(file.getObjectKey());
                    sysFileMapper.physicalDeleteById(file.getId());
                    purgedThisBatch++;
                }
                totalPurged += purgedThisBatch;

                // 整批都被保护了 —— 再查下去还是同一批，直接收工
                if (purgedThisBatch == 0) {
                    break;
                }
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }

            if (totalPurged > 0 || totalProtected > 0) {
                log.info("清理已删除文件完成：物理删除 {} 条，因回收站未过期而保留 {} 条（恢复窗口 {} 天）",
                        totalPurged, totalProtected, retentionDays);
            } else {
                log.debug("清理已删除文件：无需清理");
            }
        } catch (Exception e) {
            // 兜底：任务失败不影响后续调度
            log.error("清理已删除文件失败", e);
        }
    }
}
