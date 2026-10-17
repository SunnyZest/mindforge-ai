package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SalaryEstimate;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 薪酬估算 Mapper
 * <p>
 * 只用 MyBatis-Plus 自带的 CRUD，没有自定义 SQL。
 * ⚠️ 这张表没有 {@code deleted} 列 —— 估算记录只增不改。
 *
 * @author crx
 * @since 2026-10-17
 */
@Mapper
public interface SalaryEstimateMapper extends BaseMapper<SalaryEstimate> {
}
