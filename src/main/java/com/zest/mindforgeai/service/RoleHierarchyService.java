package com.zest.mindforgeai.service;

/**
 * 角色层级 —— 垂直越权防护。
 * <p>
 * 解决的问题：光靠 {@code @PreAuthorize} 只能判断「有没有某个权限」，
 * 判断不了「能不能操作这个对象」。比如 ADMIN 有 {@code admin:user:disable} 权限，
 * 那他能禁用 SUPER_ADMIN 的账号吗？权限码层面他是「有权限」的，
 * 但从层级上讲他不该动比自己高的人。
 * <p>
 * 所以规则是：<b>只能操作层级严格小于自己的角色 / 用户</b>。
 * <ul>
 *   <li>SUPER_ADMIN（100）能操作 USER（10）和 ADMIN（50），但动不了自己</li>
 *   <li>ADMIN（50）只能操作 USER（10）</li>
 *   <li>USER（10）谁都动不了</li>
 * </ul>
 * 「严格小于」而不是「小于等于」，是为了防止把自己锁死 ——
 * 超管改不了自己的角色权限，就没法误操作把权限清空、导致系统失去管理员。
 *
 * @author crx
 * @since 2026-10-07
 */
public interface RoleHierarchyService {

    /** 当前登录用户的最高角色层级；没有任何启用角色时返回 0 */
    int currentUserLevel();

    /** 某个用户的最高角色层级 */
    int userLevel(Long userId);

    /** 某个角色的层级 */
    int roleLevel(Long roleId);

    /**
     * 校验当前用户能否操作指定层级的对象。
     *
     * @param targetLevel 目标层级
     * @param targetDesc  目标描述，用于错误信息，如「该角色」「该用户」
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         层级不足时抛 FORBIDDEN
     */
    void requireOperable(int targetLevel, String targetDesc);

    /** 校验能否操作指定角色（等价于 {@code requireOperable(roleLevel(roleId), "该角色")}） */
    void requireRoleOperable(Long roleId);

    /** 校验能否操作指定用户 */
    void requireUserOperable(Long userId);
}
