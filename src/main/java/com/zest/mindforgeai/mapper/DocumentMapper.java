package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.vo.AdminDocumentVO;
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

    /**
     * 管理端文档分页列表，<b>不按 user_id 过滤</b>（管理员看所有人的）。
     * <p>
     * ⚠️ 返回的 {@code status}（聚合总状态）是 <b>null</b> ——
     * 它由 {@code DocumentStatusUtils.aggregate()} 算出来，不是表里的列，
     * 在 SQL 里重算等于把规则抄第二遍。调用方拿到结果后要自己填。
     *
     * @param knowledgeBaseId 按知识库筛选，可空
     * @param userId          按上传者筛选，可空
     * @param keyword         文件名模糊匹配，可空
     * @param status          原始状态，匹配任一阶段（parse/chunk/vector），可空
     */
    IPage<AdminDocumentVO> selectAdminPage(IPage<AdminDocumentVO> page,
                                           @Param("knowledgeBaseId") Long knowledgeBaseId,
                                           @Param("userId") Long userId,
                                           @Param("keyword") String keyword,
                                           @Param("status") String status);

    /** 管理端文档详情，同上但不分页。status 同样为 null，需要调用方填 */
    AdminDocumentVO selectAdminById(@Param("id") Long id);
}
