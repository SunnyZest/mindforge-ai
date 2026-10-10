package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.ResumeMatchDTO;
import com.zest.mindforgeai.pojo.vo.ResumeMatchVO;

/**
 * 简历-JD 匹配分析服务（用户端）。
 * <p>
 * <b>所有方法都只操作当前登录用户自己的数据</b>：查询直接带 user_id 条件，
 * 按 id 操作的一律先走归属校验。
 * <p>
 * ★ <b>为什么和 {@link ResumeService#optimize} 分开</b>：
 * 优化是「这份简历自己怎么写得更好」（只要简历就够），
 * 匹配是「对【这个岗位】够不够」（必须有 JD 才成立）。
 * 输入不同、结论维度也不同，合在一起会让两边都变模糊。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface ResumeJobAnalysisService {

    /**
     * 触发匹配分析（<b>慢接口</b>，调模型，实测 100~200 秒）。
     * <p>
     * ★ 结果的核心是五类结论（已匹配 / 表达缺口 / 证据不足 / 真实缺口 / 待确认），
     * {@code overallScore} 只是展示辅助。
     * <p>
     * ⚠️ <b>同一对（简历 + 职位）只保留一条</b>：重跑会覆盖上一条。
     * 匹配结果是「当前状态的快照」—— 简历改了之后再匹配就是新结论，
     * 旧结论留着反而让前端分不清哪条是当前的。
     * <p>
     * 前置条件：简历和 JD <b>都已解析过</b>（匹配的输入就是这两段原文）。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         简历/职位不存在（404）/ 还没解析过（400）/ AI 服务不可用（502、504）
     */
    ResumeMatchVO analyze(ResumeMatchDTO dto);

    /**
     * 取最近一次分析结果（<b>快接口</b>，不调模型）。
     * <p>
     * 给「进页面先看上次结果」用 —— 不然用户每刷新一次就要重跑一次模型。
     * <p>
     * ⚠️ 按 {@code user_id} 过滤，所以传别人的 resumeId 只会得到 404，
     * 不会泄漏别人的分析结果。这里刻意<b>不再查一遍简历/职位归属</b>：
     * user_id 条件本身就是安全边界，多查两次主键没有额外价值。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException 还没做过匹配（404）
     */
    ResumeMatchVO getLatest(Long resumeId, Long jobId);
}
