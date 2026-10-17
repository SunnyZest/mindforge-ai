package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.SalaryEstimateDTO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.SalaryEstimateVO;

/**
 * 薪酬估算。
 *
 * @author crx
 * @since 2026-10-17
 */
public interface SalaryEstimateService {

    /**
     * 发起一次估算。
     * <p>
     * 同一用户 + 同参数在 7 天内会直接复用旧结果（{@code cached=true}），不重复调模型。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException 参数非法或 AI 调用失败
     */
    SalaryEstimateVO estimate(SalaryEstimateDTO dto);

    /** 当前用户的估算历史（分页，按时间倒序）。 */
    PageResultVO<SalaryEstimateVO> history(long page, long pageSize);
}
