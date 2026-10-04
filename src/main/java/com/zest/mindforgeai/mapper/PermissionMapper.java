package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限表 Mapper
 *
 * @author crx
 * @since 2026-10-03
 */
@Mapper
public interface PermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 查询用户拥有的权限 code 列表：用户 → 角色 → 角色权限 → 权限。
     * <p>
     * SQL 见 {@code resources/mapper/PermissionMapper.xml}。
     * <p>
     * 表之间没有外键，关联写在 SQL 里；DISTINCT 用于用户拥有多个角色时的去重。
     * sys_permission 表没有 deleted 列，无需过滤。
     */
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
