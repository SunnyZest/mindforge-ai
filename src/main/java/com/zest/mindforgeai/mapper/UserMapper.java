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

    /**
     * 统计【活跃】用户的同邮箱数（{@code deleted = 0}）。
     * <p>
     * 用途：找回密码时判断「这个邮箱有没有注册过」。刻意和
     * {@link #countByEmailIgnoreDeleted} 分成两个方法 ——
     * 邮箱永久占用（注销的也不能再注册），但已注销的账号不能被找回，
     * 所以这里只该数活着的。
     */
    int countByEmailActive(@Param("email") String email);

    /**
     * 统计使用该手机号的<b>活跃</b>用户数（{@code deleted = 0}）。
     * <p>
     * ⚠️ 手机号和邮箱的规则<b>刻意不同</b>：
     * <ul>
     *   <li>邮箱 / 用户名：一经使用<b>永久占用</b>（{@code uk_user_email} 不管逻辑删除）；</li>
     *   <li>手机号：<b>注销后可以给别人用</b> —— 号码是运营商资源，长期占用会坑到新机主。</li>
     * </ul>
     * 所以数据库上的 {@code uk_user_phone} 是<b>函数唯一索引</b>
     * （{@code CASE WHEN deleted = 0 THEN phone ELSE NULL END}），
     * 已删除的行在索引里是 NULL，不参与唯一性。这里的查询要和那个索引口径一致。
     *
     * @param excludeUserId 排除自己（修改资料时用），可为 null
     */
    int countByPhoneActive(@Param("phone") String phone,
                           @Param("excludeUserId") Long excludeUserId);
}
