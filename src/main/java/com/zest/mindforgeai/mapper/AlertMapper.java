package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysAlert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 高危操作告警 Mapper。查询全部走 MyBatis-Plus 通用方法，无自定义 SQL。
 *
 * @author crx
 * @since 2026-10-11
 */
@Mapper
public interface AlertMapper extends BaseMapper<SysAlert> {
}
