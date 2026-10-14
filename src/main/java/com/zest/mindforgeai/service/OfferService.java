package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.dto.OfferCompareDTO;
import com.zest.mindforgeai.pojo.dto.OfferCreateDTO;
import com.zest.mindforgeai.pojo.dto.OfferStatusDTO;
import com.zest.mindforgeai.pojo.dto.OfferUpdateDTO;
import com.zest.mindforgeai.pojo.entity.JobOffer;
import com.zest.mindforgeai.pojo.vo.OfferCompareVO;
import com.zest.mindforgeai.pojo.vo.OfferVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * Offer 对比（五维打分表）。
 * <p>
 * 纯业务 CRUD + 一次本地加权计算，<b>不调 AI</b> —— 决策打分是用户自己的事，
 * 让模型替他打分反而越界。
 *
 * @author crx
 * @since 2026-10-14
 */
public interface OfferService extends IService<JobOffer> {

    /** 分页列表。status 可空（不过滤）；keyword 匹配公司名/岗位名。 */
    PageResultVO<OfferVO> pageOffers(long page, long pageSize, String keyword, String status,
                                     String sortBy, String sortOrder);

    OfferVO getDetail(Long id);

    OfferVO create(OfferCreateDTO dto);

    OfferVO update(Long id, OfferUpdateDTO dto);

    void delete(Long id);

    OfferVO updateStatus(Long id, OfferStatusDTO dto);

    /**
     * 多 Offer 对比：按权重算加权总分并排名，给出各维度最优和一句决策建议。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         数量不在 2~5、id 不合法、权重全为 0、或有不属于自己的 Offer
     */
    OfferCompareVO compare(OfferCompareDTO dto);
}
