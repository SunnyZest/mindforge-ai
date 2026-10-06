package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.ChunkBatchDTO;
import com.zest.mindforgeai.pojo.dto.DocumentStatusUpdateDTO;
import com.zest.mindforgeai.pojo.vo.DocumentClaimVO;

import java.util.List;

/**
 * 内部文档服务 —— 给 Python AI 服务用。
 * <p>
 * <b>为什么需要它</b>：按文档 §十七 的原则，Python 不碰数据库、不判权限。
 * 那它处理完文档，状态怎么回写？只能通过 Java 的接口。
 * 这组方法就是这个「回写通道」，文档里原本漏了这一环。
 * <p>
 * <b>与文档 §十七 的区别</b>：
 * <ul>
 *   <li>{@code /internal/ai/v1/**} —— Java <b>调</b> Python（Python 提供）</li>
 *   <li>本接口 —— Python <b>调</b> Java（Java 提供）</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-06
 */
public interface InternalDocumentService {

    /**
     * 认领一批待处理文档（{@code parse_status = PENDING}），并把它们改成 {@code PARSING}。
     * <p>
     * 返回的每条都带<b>预签名下载地址</b> —— 这样 Python 不需要配 OSS 的 AK/SK。
     * <p>
     * 多实例安全：底层用 {@code FOR UPDATE SKIP LOCKED}，不同实例拿到不同的文档。
     */
    List<DocumentClaimVO> claimPending(int limit);

    /** 回写处理状态（字段为 null 表示不修改） */
    void updateStatus(Long documentId, DocumentStatusUpdateDTO dto);

    /**
     * 批量写入分块，返回写入条数。
     * <p>
     * 幂等：内部先按 {@code document_id} 物理删除旧分块再插入 ——
     * 重复消费同一条消息不会撞 {@code uk_doc_chunk_index}。
     */
    int saveChunks(Long documentId, ChunkBatchDTO dto);

    /**
     * 回收「认领后超时未回写」的文档（PARSING → PENDING），返回回收条数。
     * <p>
     * 防的是「Python 认领后崩了」—— 没有这一步，那些文档会永远卡在 PARSING。
     */
    int reclaimTimedOut();
}
