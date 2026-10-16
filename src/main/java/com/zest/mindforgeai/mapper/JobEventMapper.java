package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 求职日程 Mapper。全部走 MyBatis-Plus 通用方法，无自定义 SQL。
 *
 * @author crx
 * @since 2026-10-16
 */
@Mapper
public interface JobEventMapper extends BaseMapper<JobEvent> {
}
