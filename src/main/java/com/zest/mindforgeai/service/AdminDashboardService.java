package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.DashboardVO;

/**
 * 管理后台首页统计。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface AdminDashboardService {

    /** 首页统计（用户数 / 知识库数 / 文档数 / AI 调用次数 / token 消耗） */
    DashboardVO getDashboard();
}
