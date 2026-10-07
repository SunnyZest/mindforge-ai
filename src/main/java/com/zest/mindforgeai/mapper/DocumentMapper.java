package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档 Mapper
 *
 * @author crx
 * @since 2026-10-05
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 认领一批待处理文档，供 Python AI 服务使用。
     * <p>
     * <b>关键是 {@code FOR UPDATE SKIP LOCKED}</b>（MySQL 8.0+）：
     * 多个 Python 实例同时来认领时，会自动跳过已被别人锁住的行，
     * 而不是排队等待 —— 这样天然支持水平扩容，也不会重复处理同一份文档。
     * <p>
     * 必须在事务里调用，否则行锁会立刻释放，SKIP LOCKED 就失去意义了。
     * <p>
     * SQL 见 {@code resources/mapper/DocumentMapper.xml}。
     */
    List<Document> selectPendingForClaim(@Param("limit") int limit);

    /**
     * 回收「认领了但一直没回写状态」的文档。
     * <p>
     * 场景：Python 认领后进程崩了，文档会永远停在 {@code PARSING}，
     * 再也没人处理它。这个操作把超时的改回 {@code PENDING} 让别人重新认领。
     *
     * @param deadline 早于这个时间被更新的 PARSING 文档才算超时
     */
    int reclaimTimedOut(@Param("deadline") LocalDateTime deadline);

    /**
     * 查出「已删除但向量还没清」的文档，供 AI 服务清理向量库。
     * <p>
     * <b>为什么需要</b>：删除文档只做逻辑删除（{@code deleted = 1}），
     * 但向量库里的向量不会自动消失 —— 会留下「孤儿向量」，
     * 检索时可能召回已被删除的文档内容。
     * <p>
     * 这里不加 {@code FOR UPDATE SKIP LOCKED}：清理是幂等的
     * （重复删同一个文档的向量无害），没必要为了去重引入锁的开销。
     */
    List<Document> selectPendingVectorPurge(@Param("limit") int limit);

    /**
     * 标记某个文档的向量已清除。
     * <p>
     * 带 {@code deleted = 1} 条件是为了防御：只标记确实被删除的文档，
     * 避免误标一个还在用的文档（那样它的向量就永远不会被清了）。
     */
    int markVectorPurged(@Param("id") Long id);
}
