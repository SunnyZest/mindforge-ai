package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zest.mindforgeai.pojo.entity.KnowledgeBase;
import com.zest.mindforgeai.pojo.vo.AdminKnowledgeBaseVO;
import com.zest.mindforgeai.pojo.vo.KnowledgeBaseStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 知识库 Mapper
 *
 * @author crx
 * @since 2026-10-05
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 知识库分页列表，<b>document_count 实时计算</b>而不是读表里的冗余字段。
     * <p>
     * 表里虽然有个 {@code document_count} 冗余字段，但它没有任何地方维护，
     * 永远是 0。与其加一个「文档模块必须记得调用」的更新入口（漏一次就永久错），
     * 不如在查询时实时算 —— 分页只有 20 行，子查询代价可忽略。
     * <p>
     * SQL 见 {@code resources/mapper/KnowledgeBaseMapper.xml}。
     *
     * @param page    分页对象，由 MyBatis-Plus 分页插件填充
     * @param userId  归属用户（归属校验在这里就完成，不依赖上层）
     * @param keyword 名称模糊匹配，可空
     * @param status  状态筛选，可空
     */
    IPage<KnowledgeBase> selectPageWithDocumentCount(IPage<KnowledgeBase> page,
                                                     @Param("userId") Long userId,
                                                     @Param("keyword") String keyword,
                                                     @Param("status") Integer status);

    /**
     * 知识库统计（文档 §十五）。
     * <p>
     * 用子查询一次查完 4 个指标，避免 4 次往返。
     * SQL 见 {@code resources/mapper/KnowledgeBaseMapper.xml}。
     */
    KnowledgeBaseStatsVO selectStats(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * 管理端知识库分页列表，<b>不按 user_id 过滤</b>（管理员看所有用户的）。
     * <p>
     * 与 {@link #selectPageWithDocumentCount} 分开写，而不是给它加一个
     * 「可选跳过归属校验」的开关 —— 那种开关一旦传错参数，用户端接口就会漏数据。
     * <p>
     * 多返回归属用户 id 和用户名（关联 user 表，不过滤 deleted：
     * 用户被删了，他的知识库仍要能看出是谁的）。
     *
     * @param userId  按归属用户筛选，可空
     * @param keyword 名称模糊匹配，可空
     * @param status  状态筛选，可空
     */
    IPage<AdminKnowledgeBaseVO> selectAdminPage(IPage<AdminKnowledgeBaseVO> page,
                                                @Param("userId") Long userId,
                                                @Param("keyword") String keyword,
                                                @Param("status") Integer status);

    /** 管理端知识库详情，同上但不分页。查不到（或已逻辑删除）返回 null */
    AdminKnowledgeBaseVO selectAdminById(@Param("id") Long id);
}
