package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.mapper.DashboardMapper;
import com.zest.mindforgeai.pojo.vo.DashboardVO;
import com.zest.mindforgeai.service.AdminDashboardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 管理后台首页统计实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private final DashboardMapper dashboardMapper;

    public AdminDashboardServiceImpl(DashboardMapper dashboardMapper) {
        this.dashboardMapper = dashboardMapper;
    }

    @Override
    public DashboardVO getDashboard() {
        return dashboardMapper.selectDashboard();
    }
}
