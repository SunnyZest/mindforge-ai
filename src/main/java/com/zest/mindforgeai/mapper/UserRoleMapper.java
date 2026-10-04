package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysUserRole;
import com.zest.mindforgeai.pojo.vo.UserRoleItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 用户-角色关联 Mapper
 *
 * @author crx
 * @since 2026-10-03
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 批量查询多个用户的角色 code（列表页用，避免逐行查询造成 N+1）。
     * <p>
     * SQL 见 {@code resources/mapper/UserRoleMapper.xml}。
     *
     * @param userIds 调用方需保证非空集合（空集合会生成非法的 IN ()）
     */
    List<UserRoleItemVO> selectRoleCodesByUserIds(@Param("userIds") Collection<Long> userIds);
}
