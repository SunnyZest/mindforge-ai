package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色表 Mapper
 *
 * @author crx
 * @since 2026-10-03
 */
@Mapper
public interface RoleMapper extends BaseMapper<SysRole> {
}
