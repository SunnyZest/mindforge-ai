package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.RoleMapper;
import com.zest.mindforgeai.mapper.UserRoleMapper;
import com.zest.mindforgeai.pojo.entity.SysRole;
import com.zest.mindforgeai.pojo.entity.SysUserRole;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.RoleHierarchyService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 角色层级实现。
 *
 * @author crx
 * @since 2026-10-07
 */
@Slf4j
@Service
public class RoleHierarchyServiceImpl implements RoleHierarchyService {

    /** 角色启用状态 */
    private static final int STATUS_ENABLED = 1;

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuditService auditService;

    public RoleHierarchyServiceImpl(RoleMapper roleMapper,
                                    UserRoleMapper userRoleMapper,
                                    AuditService auditService) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.auditService = auditService;
    }

    @Override
    public int currentUserLevel() {
        return userLevel(SecurityUtils.getUserId());
    }

    @Override
    public int userLevel(Long userId) {
        if (userId == null) {
            return 0;
        }

        List<Long> roleIds = userRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();

        if (roleIds.isEmpty()) {
            return 0;
        }

        return maxLevel(roleIds);
    }

    @Override
    public int roleLevel(Long roleId) {
        if (roleId == null) {
            return 0;
        }
        return maxLevel(List.of(roleId));
    }

    @Override
    public void requireOperable(int targetLevel, String targetDesc) {
        int mine = currentUserLevel();

        if (targetLevel >= mine) {
            log.warn("垂直越权拦截：当前用户 {} 层级 {}，试图操作层级 {} 的{}",
                    SecurityUtils.getUserId(), mine, targetLevel, targetDesc);

            // 记审计：垂直越权尝试是「内部威胁」的高危信号，必须留痕。
            // 光打应用日志不够 —— 日志会滚动，审计表才是能事后追查的地方。
            auditService.recordFailure(AuditConstants.ACTION_ACCESS_DENIED,
                    AuditConstants.RESOURCE_ROLE, null,
                    Map.of("reason", "垂直越权：层级不足",
                           "myLevel", mine,
                           "targetLevel", targetLevel,
                           "target", targetDesc));

            throw new BizException(ErrorCode.FORBIDDEN,
                    String.format("无权操作%s —— 只能操作角色层级低于自己的对象", targetDesc));
        }
    }

    @Override
    public void requireRoleOperable(Long roleId) {
        requireOperable(roleLevel(roleId), "该角色");
    }

    @Override
    public void requireUserOperable(Long userId) {
        requireOperable(userLevel(userId), "该用户");
    }

    // ---------- 内部 ----------

    /**
     * 取这些角色里最高的层级。
     * <p>
     * <b>只算启用状态的角色</b> —— 角色被禁用就不该再产生权限效果。
     * 比如某个超管角色被禁用后，挂着它的用户层级应该掉回去，
     * 否则「禁用角色」就形同虚设。
     */
    private int maxLevel(List<Long> roleIds) {
        List<SysRole> roles = roleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery()
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getStatus, STATUS_ENABLED));

        return roles.stream()
                .map(SysRole::getLevel)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }
}
