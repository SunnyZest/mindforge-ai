package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper
 *
 * @author crx
 * @since 2026-10-03
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 统计同名用户数，<b>包含已逻辑删除的</b>。
     * <p>
     * 为什么要忽略 {@code deleted}：user 表上有 {@code uk_user_username} 唯一索引，
     * 而数据库的唯一约束<b>不管逻辑删除</b> —— 所以「用户名一经使用即永久占用」
     * 是本项目的既定规则（类似 GitHub，防冒名）。
     * <p>
     * MyBatis-Plus 的 {@code count()} 会自动加 {@code deleted = 0}，查不到已删除的记录，
     * 于是会绕过业务检查、直接撞索引（表现为兜底的 40902「数据已存在」，提示不准确）。
     * 这个方法绕过 MP 的逻辑删除，用于给出准确的提示。
     * <p>
     * SQL 见 {@code resources/mapper/UserMapper.xml}。
     */
    int countByUsernameIgnoreDeleted(@Param("username") String username);

    /** 统计同邮箱用户数，包含已逻辑删除的（同上，对应 {@code uk_user_email}） */
    int countByEmailIgnoreDeleted(@Param("email") String email);
}
