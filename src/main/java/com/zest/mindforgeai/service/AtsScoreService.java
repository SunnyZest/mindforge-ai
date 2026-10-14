package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.AtsScoreVO;

/**
 * ATS 关键词跑分。
 * <p>
 * 模拟招聘系统（ATS）初筛的机械视角：JD 里的关键词在简历里出现了没有。
 * <b>纯本地计算、不调模型</b> —— 真实 ATS 就是关键词匹配，用模型算反而偏离语义，
 * 而且同样的输入必须给出同样的分（用户会反复对照着改简历）。
 * <p>
 * 因此本服务<b>不落库</b>：毫秒级纯函数式计算，输入不变结果不变，没有缓存的必要。
 *
 * @author crx
 * @since 2026-10-14
 */
public interface AtsScoreService {

    /**
     * 给「某份简历 对 某个职位」跑一次 ATS 关键词分。
     *
     * @param resumeId 简历 id（会校验归属）
     * @param jobId    职位 id（会校验归属）
     * @throws com.zest.mindforgeai.common.exception.BizException 简历/JD 没解析过、
     *         或 JD 没有可用的技能项（没解析过就没有）
     */
    AtsScoreVO score(Long resumeId, Long jobId);
}
