package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.RecycleBin;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回收站 Mapper。全部走 MyBatis-Plus 通用方法，无自定义 SQL。
 *
 * <p>恢复 / 彻底删除要动的「原表」不在这里 —— 那些是动态表名，
 * 统一在 {@code RecycleBinServiceImpl} 里用 JdbcTemplate 执行（见那里的说明）。
 *
 * @author crx
 * @since 2026-10-22
 */
@Mapper
public interface RecycleBinMapper extends BaseMapper<RecycleBin> {
}
