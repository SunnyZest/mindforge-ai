package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.KnowledgeBase;
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
     * 知识库统计（文档 §十五）。
     * <p>
     * 用子查询一次查完 4 个指标，避免 4 次往返。
     * SQL 见 {@code resources/mapper/KnowledgeBaseMapper.xml}。
     */
    KnowledgeBaseStatsVO selectStats(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
