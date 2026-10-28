package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.AdminUserCreateDTO;
import com.zest.mindforgeai.pojo.dto.UserUpdateDTO;
import com.zest.mindforgeai.pojo.vo.AdminUserDetailVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.UserInfoVO;
import com.zest.mindforgeai.pojo.vo.UserSummaryVO;

import java.util.List;

/**
 * 管理端用户管理服务。
 * <p>
 * 与面向用户自身的 {@link UserService} 分开：这里是<b>管理员视角</b>的跨用户操作，
 * 权限由 Controller 的 {@code @PreAuthorize} 把关。
 *
 * @author crx
 * @since 2026-10-04
 */
public interface AdminUserService {

    /**
     * 用户分页列表。
     *
     * @param keyword  模糊匹配用户名 / 昵称 / 邮箱，可空
     * @param status   状态筛选，可空
     * @param roleCode 角色 code 筛选，可空
     */
    PageResultVO<UserSummaryVO> pageUsers(long page, long pageSize,
                                          String keyword, Integer status, String roleCode,
                                          String sortBy, String sortOrder);

    /**
     * 用户详情（基础信息 + 角色明细 + 有效权限 + 登录失败计数 + 资源占用）。
     * <p>
     * ⚠️ 返回的是 {@link AdminUserDetailVO} 而<b>不是</b> {@link UserInfoVO} ——
     * 后者与 {@code /app/auth/me} 共用，带管理端字段等于把风控数据发给用户本人。
     * 改动前这里是 UserInfoVO，字段比列表项还少，前端因此一直没接这个接口。
     */
    AdminUserDetailVO getUserDetail(Long userId);

    /** 修改用户资料（昵称 / 邮箱 / 手机号 / 头像），null 字段表示不修改 */
    void updateProfile(Long userId, UserUpdateDTO dto);

    /** 启用（1）或禁用（0）用户 */
    void updateStatus(Long userId, Integer status);

    /** 全量覆盖用户的角色（不是追加）
     * roleIds: 角色对应的id
     */
    void assignRoles(Long userId, List<Long> roleIds);

    /**
     * 创建用户。
     * <p>
     * 用户名 / 邮箱沿用「一经使用即永久占用」的规则（见 {@code UserMapper} 的说明）。
     * 未指定角色时只绑定默认角色 USER。
     *
     * @return 新建用户的信息（含角色与权限）
     */
    UserInfoVO createUser(AdminUserCreateDTO dto);

    /**
     * 删除（逻辑）用户。
     * <p>
     * 会一并清理 {@code sys_user_role} 关联、撤销该用户全部 refresh token。
     * 账号本身是<b>逻辑删除</b> —— 数据留着可追溯，但用户名/邮箱永久占用。
     */
    void deleteUser(Long userId);

    /** 批量更新用户状态（启用/禁用），返回成功条数 */
    int batchUpdateStatus(List<Long> ids, Integer status);

    /** 批量分配角色，返回成功条数 */
    int batchAssignRoles(List<Long> ids, List<Long> roleIds);

    /** 批量删除用户，返回成功条数 */
    int batchDeleteUsers(List<Long> ids);
}
