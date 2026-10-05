package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.mapper.PermissionMapper;
import com.zest.mindforgeai.pojo.entity.SysPermission;
import com.zest.mindforgeai.pojo.vo.PermissionVO;
import com.zest.mindforgeai.service.PermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限查询服务实现
 *
 * @author crx
 * @since 2026-10-05
 */
@Service
public class PermissionServiceImpl extends ServiceImpl<PermissionMapper, SysPermission>
        implements PermissionService {

    @Override
    public List<PermissionVO> listAll() {
        // sys_permission 没有 deleted 列，MP 不会追加逻辑删除条件，这里无需额外处理
        return list(new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getCode))
                .stream()
                .map(PermissionVO::of)
                .toList();
    }
}
