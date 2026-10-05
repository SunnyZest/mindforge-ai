package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色表 Mapper
 *
 * @author crx
 * @since 2026-10-03
 */
@Mapper
public interface RoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询用户拥有的角色 code 列表。
     * <p>
     * SQL 见 {@code resources/mapper/RoleMapper.xml}。
     * <p>
     * 表之间没有外键，关联写在 SQL 里。
     * 注意：自定义 SQL 不经过 MyBatis-Plus 的逻辑删除，deleted 需要手动过滤。
     */
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /**
     * 统计同 code 的角色数，<b>包含已逻辑删除的</b>。
     * <p>
     * 与 user 表同理：sys_role 上有 {@code uk_role_code} 唯一索引，
     * 唯一约束不管逻辑删除，所以「角色 code 一经使用即永久占用」是既定规则。
     * MP 的 {@code count()} 会自动加 {@code deleted = 0}，这里故意不过滤。
     * <p>
     * SQL 见 {@code resources/mapper/RoleMapper.xml}。
     */
    int countByCodeIgnoreDeleted(@Param("code") String code);
}
