package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.AiUsageRecord;
import com.zest.mindforgeai.pojo.vo.UsageByModelVO;
import com.zest.mindforgeai.pojo.vo.UsageDailyVO;
import com.zest.mindforgeai.pojo.vo.UsageSummaryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 使用量 Mapper。
 *
 * <p>三个聚合查询都写在 {@code resources/mapper/AiUsageRecordMapper.xml} ——
 * 本项目自定义 SQL 一律走 XML，不用 {@code @Select}。
 *
 * @author crx
 * @since 2026-10-10
 */
@Mapper
public interface AiUsageRecordMapper extends BaseMapper<AiUsageRecord> {

    /**
     * 时间范围内的总量。
     *
     * @param from 含（左闭）
     * @param to   不含（右开）—— 用左闭右开，跨天统计不会重复也不会漏
     */
    UsageSummaryVO selectSummary(@Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    /** 按天汇总，只返回有记录的天（没有记录的天不补 0 —— 前端画图时自己补）。 */
    List<UsageDailyVO> selectDaily(@Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /** 按「场景 + 模型」汇总，按 token 降序。 */
    List<UsageByModelVO> selectByModel(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);
}
