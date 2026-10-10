package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.ResumeJobAnalysis;
import org.apache.ibatis.annotations.Mapper;

/**
 * 简历职位匹配分析 Mapper
 * <p>
 * 只用 MyBatis-Plus 自带的 CRUD，没有自定义 SQL。
 * ⚠️ 这张表没有 {@code deleted} 列，删除是物理删。
 *
 * @author crx
 * @since 2026-10-10
 */
@Mapper
public interface ResumeJobAnalysisMapper extends BaseMapper<ResumeJobAnalysis> {
}
