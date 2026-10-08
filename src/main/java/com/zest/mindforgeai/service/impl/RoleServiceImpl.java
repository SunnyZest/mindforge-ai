package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RoleConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.PermissionMapper;
import com.zest.mindforgeai.mapper.RoleMapper;
import com.zest.mindforgeai.mapper.RolePermissionMapper;
import com.zest.mindforgeai.mapper.UserRoleMapper;
import com.zest.mindforgeai.pojo.dto.RoleCreateDTO;
import com.zest.mindforgeai.pojo.dto.RoleUpdateDTO;
import com.zest.mindforgeai.pojo.entity.SysPermission;
import com.zest.mindforgeai.pojo.entity.SysRole;
import com.zest.mindforgeai.pojo.entity.SysRolePermission;
import com.zest.mindforgeai.pojo.entity.SysUserRole;
import com.zest.mindforgeai.pojo.vo.PermissionVO;
import com.zest.mindforgeai.pojo.vo.RolePermissionCountVO;
import com.zest.mindforgeai.pojo.vo.RoleVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.RoleHierarchyService;
import com.zest.mindforgeai.service.RoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色管理服务实现
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, SysRole> implements RoleService {

    /** 内置角色：由系统初始化写入，是权限体系的地基，不允许删除 */
    private static final Set<String> BUILTIN_CODES =
            Set.of(RoleConstants.USER, RoleConstants.ADMIN, RoleConstants.SUPER_ADMIN);

    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;
    private final AuditService auditService;
    private final RoleHierarchyService roleHierarchyService;

    public RoleServiceImpl(RolePermissionMapper rolePermissionMapper,
                           UserRoleMapper userRoleMapper,
                           PermissionMapper permissionMapper,
                           AuditService auditService,
                           RoleHierarchyService roleHierarchyService) {
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.auditService = auditService;
        this.roleHierarchyService = roleHierarchyService;
    }

    @Override
    public List<RoleVO> listRoles() {
        List<SysRole> roles = list(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));

        // 一次查出所有角色的权限数，避免逐行统计造成 N+1
        Map<Long, Long> countMap = rolePermissionMapper.selectPermissionCountGroupByRole()
                .stream()
                .collect(Collectors.toMap(RolePermissionCountVO::getRoleId,
                        RolePermissionCountVO::getCnt));

        return roles.stream()
                .map(r -> RoleVO.of(r, countMap.getOrDefault(r.getId(), 0L), isBuiltin(r.getCode())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO createRole(RoleCreateDTO dto) {
        String code = dto.getCode().trim();

        // code 是权限体系的标识，必须唯一。
        // 用「包含已逻辑删除」的统计 —— uk_role_code 唯一约束不管逻辑删除，
        // 所以 code 是【永久占用】的：删掉一个角色后，它的 code 不能再用。
        // 用 MP 的 count() 会漏掉已删除的角色，导致检查通过后撞索引（只能给模糊提示）。
        if (baseMapper.countByCodeIgnoreDeleted(code) > 0) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "角色 code 已存在：" + code);
        }

        SysRole role = new SysRole()
                .setName(dto.getName())
                .setCode(code)
                .setDescription(dto.getDescription())
                .setStatus(1);
        save(role);

        log.info("创建角色 id={} code={} name={}", role.getId(), code, dto.getName());
        auditService.recordSuccess(AuditConstants.ACTION_ROLE_CREATE,
                AuditConstants.RESOURCE_ROLE, String.valueOf(role.getId()),
                Map.of("name", dto.getName(), "code", code));

        return RoleVO.of(role, 0L, isBuiltin(code));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(Long roleId, RoleUpdateDTO dto) {
        SysRole role = getById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在");
        }

        // MP 默认策略：null 字段不进 UPDATE → 天然支持局部更新
        updateById(new SysRole()
                .setId(roleId)
                .setName(dto.getName())
                .setDescription(dto.getDescription())
                .setStatus(dto.getStatus())
                .setUpdatedAt(LocalDateTime.now()));

        log.info("修改角色 id={} code={}", roleId, role.getCode());

        // 用 LinkedHashMap 而不是 Map.of —— 后者不允许 null 值
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", role.getCode());
        detail.put("beforeName", role.getName());
        detail.put("afterName", dto.getName());
        detail.put("beforeStatus", role.getStatus());
        detail.put("afterStatus", dto.getStatus());
        auditService.recordSuccess(AuditConstants.ACTION_ROLE_UPDATE,
                AuditConstants.RESOURCE_ROLE, String.valueOf(roleId), detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long roleId) {
        SysRole role = getById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在");
        }
        if (isBuiltin(role.getCode())) {
            throw new BizException(ErrorCode.FORBIDDEN, "内置角色不允许删除：" + role.getCode());
        }

        // ★ 垂直越权防护：不能删层级 ≥ 自己的角色
        roleHierarchyService.requireRoleOperable(roleId);

        // ⚠️ 没有外键级联，必须手动清理两张关联表。
        //    而且这两张表【没有 deleted 列】→ 只能物理删除，不能用逻辑删除。
        int permBindings = rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        int userBindings = userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId));

        // sys_role 有 deleted 列 → removeById 是逻辑删除
        removeById(roleId);

        log.info("删除角色 id={} code={}，清理权限绑定 {} 条、用户绑定 {} 条",
                roleId, role.getCode(), permBindings, userBindings);

        // 写进审计日志
        auditService.recordSuccess(AuditConstants.ACTION_ROLE_DELETE,
                AuditConstants.RESOURCE_ROLE, String.valueOf(roleId),
                Map.of("code", role.getCode(),
                       "name", role.getName() == null ? "" : role.getName(),
                       "permissionBindingsRemoved", permBindings,
                       "userBindingsRemoved", userBindings));
    }

    /**
     * 角色已绑定的权限
     * @param roleId
     * @return
     */
    @Override
    public List<PermissionVO> listRolePermissions(Long roleId) {
        if (getById(roleId) == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在");
        }
        // 先根据roleId查对应的角色权限列表id
        // 再根据角色权限列表id拿到 对应权限列表
        return listPermissionsByIds(listPermissionIdsByRole(roleId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        SysRole role = getById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "角色不存在");
        }

        // ★ 垂直越权防护：不能改层级 ≥ 自己的角色的权限。
        // 现在只有 SUPER_ADMIN 能调这个接口，所以改不了自己（层级相同即拒绝）——
        // 这正好防止误操作把自己的权限清空、导致系统失去管理员。
        // 将来若给 ADMIN 开了这个权限，这一行就是防止他改 SUPER_ADMIN 的关键。
        roleHierarchyService.requireRoleOperable(roleId);

        List<Long> targetIds = permissionIds == null ? List.of()
                : permissionIds.stream().filter(Objects::nonNull).distinct().toList();

        // 校验权限都存在（没有外键，否则会插入孤儿绑定）
        if (!targetIds.isEmpty()) {
            long found = permissionMapper.selectCount(new LambdaQueryWrapper<SysPermission>()
                    .in(SysPermission::getId, targetIds));
            if (found != targetIds.size()) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "存在无效的权限 id");
            }
        }

        // 记下变更前的权限码，用于审计
        List<String> before = listPermissionsByIds(listPermissionIdsByRole(roleId))
                .stream().map(PermissionVO::getCode).toList();

        // ⚠️ 没有外键级联：先清后插，与下面两步同在一个事务里
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));
        for (Long permissionId : targetIds) {
            rolePermissionMapper.insert(new SysRolePermission()
                    .setRoleId(roleId)
                    .setPermissionId(permissionId));
        }

        List<String> after = listPermissionsByIds(targetIds)
                .stream().map(PermissionVO::getCode).toList();

        log.info("分配权限 roleId={} code={}，权限数 {} -> {}",
                roleId, role.getCode(), before.size(), after.size());

        // 审计记录完整的权限码前后对比 —— 这是「谁给哪个角色加了什么权限」的唯一依据
        auditService.recordSuccess(AuditConstants.ACTION_ROLE_ASSIGN_PERMISSION,
                AuditConstants.RESOURCE_ROLE, String.valueOf(roleId),
                Map.of("code", role.getCode(), "before", before, "after", after));
    }

    /* ---------- 内部工具 ---------- */

    private boolean isBuiltin(String code) {
        return code != null && BUILTIN_CODES.contains(code);
    }

    private List<Long> listPermissionIdsByRole(Long roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .toList();
    }

    private List<PermissionVO> listPermissionsByIds(List<Long> permissionIds) {
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                        .in(SysPermission::getId, permissionIds)
                        .orderByAsc(SysPermission::getCode))
                .stream()
                .map(PermissionVO::of)
                .toList();
    }
}
