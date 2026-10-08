package com.zest.mindforgeai.mapper;

import com.zest.mindforgeai.pojo.vo.DashboardVO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理后台首页统计 Mapper
 *
 * @author crx
 * @since 2026-10-08
 */
@Mapper
public interface DashboardMapper {

    /**
     * 一次查出首页需要的 5 个指标。
     * <p>
     * 为什么不复用各模块的 Mapper 各查一次：那样是 5 次数据库往返，
     * 而首页每次打开都会调一次。用一条 SQL 的子查询一次拿完，
     * 5 个 COUNT 都是走索引的，代价远小于 5 次网络往返。
     * <p>
     * SQL 见 {@code resources/mapper/DashboardMapper.xml}。
     */
    DashboardVO selectDashboard();
}
