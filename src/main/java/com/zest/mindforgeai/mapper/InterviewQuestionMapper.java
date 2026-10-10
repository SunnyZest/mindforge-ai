package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.InterviewQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试题 Mapper
 * <p>
 * ⚠️ 这张表没有 {@code deleted} 列，所以删的时候是物理删 ——
 * MP 的逻辑删除对它不起作用（也正因如此，写自定义 SQL 时不需要过滤 deleted）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Mapper
public interface InterviewQuestionMapper extends BaseMapper<InterviewQuestion> {
}
