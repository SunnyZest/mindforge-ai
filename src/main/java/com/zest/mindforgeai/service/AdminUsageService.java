package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.UsageByModelVO;
import com.zest.mindforgeai.pojo.vo.UsageDailyVO;
import com.zest.mindforgeai.pojo.vo.UsageSummaryVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端：AI 使用量统计。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface AdminUsageService {

    /** 时间范围内的总量。日期为 null 时默认取最近 7 天（含今天）。 */
    UsageSummaryVO summary(LocalDate from, LocalDate to);

    /** 按天汇总，只返回有记录的天。 */
    List<UsageDailyVO> daily(LocalDate from, LocalDate to);

    /** 按「场景 + 模型」汇总，按 token 降序。 */
    List<UsageByModelVO> byModel(LocalDate from, LocalDate to);
}
