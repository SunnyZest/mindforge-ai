package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.SysPermission;
import com.zest.mindforgeai.pojo.vo.PermissionVO;

import java.util.List;

/**
 * 权限查询服务。
 * <p>
 * 权限是<b>系统内置的字典数据</b>，不提供增删改 —— 只能通过 SQL 或数据初始化脚本维护。
 * 这里只提供查询，供管理端「给角色分配权限」时列出可选项。
 *
 * @author crx
 * @since 2026-10-05
 */
public interface PermissionService extends IService<SysPermission> {

    /** 全部权限，按 code 排序 */
    List<PermissionVO> listAll();
}
