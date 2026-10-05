package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysRolePermission;
import com.zest.mindforgeai.pojo.vo.RolePermissionCountVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 角色-权限关联 Mapper
 *
 * @author crx
 * @since 2026-10-05
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<SysRolePermission> {

    /**
     * 统计每个角色已绑定的权限数量（角色列表页用，避免逐行统计造成 N+1）。
     * <p>
     * SQL 见 {@code resources/mapper/RolePermissionMapper.xml}。
     */
    List<RolePermissionCountVO> selectPermissionCountGroupByRole();
}
