package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.Interview;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 面试 Mapper
 * <p>
 * 只用 MyBatis-Plus 自带的 CRUD，没有自定义 SQL。
 *
 * @author crx
 * @since 2026-10-10
 */
@Mapper
public interface InterviewMapper extends BaseMapper<Interview> {
}
