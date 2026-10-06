package com.zest.mindforgeai.task;

import com.zest.mindforgeai.service.InternalDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收「认领后超时未回写」的文档。
 * <p>
 * <b>防的是什么</b>：Python 认领了一批文档（状态改成 PARSING），然后进程崩了 /
 * 被 kill 了 / 网络断了 —— 那些文档就永远卡在 PARSING，再也没人会处理。
 * <p>
 * 这个任务把超过 {@code mindforge.internal.claim-timeout-minutes} 分钟没动静的
 * 改回 PENDING，让别的实例重新认领。
 * <p>
 * <b>超时时间要设得比「单篇文档最长处理时间」大</b>，否则会出现
 * 「还在处理中就被别人抢走」—— 同一份文档被处理两遍。
 *
 * @author crx
 * @since 2026-10-06
 */
@Slf4j
@Component
public class DocumentClaimTimeoutTask {

    private final InternalDocumentService internalDocumentService;

    public DocumentClaimTimeoutTask(InternalDocumentService internalDocumentService) {
        this.internalDocumentService = internalDocumentService;
    }

    /** 每 10 分钟扫一次（cron 可配） */
    @Scheduled(cron = "${mindforge.internal.claim-timeout-cron:0 */10 * * * ?}")
    public void reclaimTimedOut() {
        try {
            internalDocumentService.reclaimTimedOut();
        } catch (Exception e) {
            // 兜底：任务失败不影响后续调度
            log.error("回收超时文档失败", e);
        }
    }
}
