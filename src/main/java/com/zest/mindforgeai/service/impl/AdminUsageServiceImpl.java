package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.AiUsageRecordMapper;
import com.zest.mindforgeai.pojo.vo.UsageByModelVO;
import com.zest.mindforgeai.pojo.vo.UsageDailyVO;
import com.zest.mindforgeai.pojo.vo.UsageSummaryVO;
import com.zest.mindforgeai.service.AdminUsageService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author crx
 * @since 2026-10-10
 */
@Service
public class AdminUsageServiceImpl implements AdminUsageService {

    /** 不传日期时看最近这么多天（含今天）。7 天刚好够看出趋势，也不会把图挤成一团。 */
    private static final int DEFAULT_DAYS = 7;

    private final AiUsageRecordMapper usageMapper;

    public AdminUsageServiceImpl(AiUsageRecordMapper usageMapper) {
        this.usageMapper = usageMapper;
    }

    @Override
    public UsageSummaryVO summary(LocalDate from, LocalDate to) {
        Range range = resolve(from, to);
        return usageMapper.selectSummary(range.from(), range.to());
    }

    @Override
    public List<UsageDailyVO> daily(LocalDate from, LocalDate to) {
        Range range = resolve(from, to);
        return usageMapper.selectDaily(range.from(), range.to());
    }

    @Override
    public List<UsageByModelVO> byModel(LocalDate from, LocalDate to) {
        Range range = resolve(from, to);
        return usageMapper.selectByModel(range.from(), range.to());
    }

    /**
     * 把「两个可选日期」解析成一个左闭右开的时间区间。
     *
     * <p>为什么右开：{@code to} 是用户想看的<b>那一天</b>（含），换算成时间戳要取它的次日零点。
     * 直接用 {@code to.atTime(23,59,59)} 会漏掉最后一秒里的记录。
     */
    private Range resolve(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);

        if (start.isAfter(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "开始日期不能晚于结束日期");
        }
        return new Range(start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    /** 解析后的时间区间，左闭右开。 */
    private record Range(LocalDateTime from, LocalDateTime to) {
    }
}
