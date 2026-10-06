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
}
