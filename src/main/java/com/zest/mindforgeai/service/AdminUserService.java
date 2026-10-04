package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.UserUpdateDTO;
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
                                          String keyword, Integer status, String roleCode);

    /** 用户详情（含角色与权限） */
    UserInfoVO getUserDetail(Long userId);

    /** 修改用户资料（昵称 / 邮箱 / 手机号 / 头像），null 字段表示不修改 */
    void updateProfile(Long userId, UserUpdateDTO dto);

    /** 启用（1）或禁用（0）用户 */
    void updateStatus(Long userId, Integer status);

    /** 全量覆盖用户的角色（不是追加） */
    void assignRoles(Long userId, List<Long> roleIds);
}
