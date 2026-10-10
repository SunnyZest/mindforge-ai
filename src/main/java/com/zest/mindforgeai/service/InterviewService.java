package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.InterviewAnswerDTO;
import com.zest.mindforgeai.pojo.dto.InterviewCreateDTO;
import com.zest.mindforgeai.pojo.vo.InterviewAnswerResultVO;
import com.zest.mindforgeai.pojo.vo.InterviewDetailVO;
import com.zest.mindforgeai.pojo.vo.InterviewReportVO;
import com.zest.mindforgeai.pojo.vo.InterviewVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * AI 面试服务（用户端）。
 * <p>
 * <b>所有方法都只操作当前登录用户自己的面试</b>：列表查询直接带 user_id 条件，
 * 按 id 操作的一律先走归属校验。
 * <p>
 * 四个步骤各自单一职责，慢的只有三个（都调模型）：
 * <pre>
 * create    快   建一行数据，status = CREATED
 * generate  慢   出题（★ 幂等：已有题目会先清后出，所以可以直接重试）
 * start     快   开始作答，status = IN_PROGRESS
 * answer    慢   单题评分
 * finish    慢   生成复盘报告，status = FINISHED
 * </pre>
 * ⚠️ <b>为什么把「创建」和「出题」拆成两步</b>：创建失败（参数、简历不存在）
 * 和 AI 失败（超时、模型抽风）是两类问题，混在一个接口里前端说不清该提示什么；
 * 而且出题失败后用户没法重试 —— 除非把刚建好的面试删掉再来一遍。
 * 拆开之后「重试出题」就是再调一次 generate。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface InterviewService {

    /**
     * 创建面试（快，不调模型）。
     * <p>
     * {@code resumeId} / {@code jobId} 都可空，都传就是「针对这个岗位、基于这份简历」的面试。
     * 传了的话会<b>立刻校验归属</b>（不是自己的 → 404）—— 失败要早，
     * 不能等到出题时才发现简历是别人的。
     */
    InterviewVO create(InterviewCreateDTO dto);

    /** 分页列表（关键词匹配标题；status 可选过滤） */
    PageResultVO<InterviewVO> pageInterviews(long page, long pageSize, String keyword, String status);

    /** 详情（含题目列表）。⚠️ 参考答案要点只在面试结束后返回 */
    InterviewDetailVO getDetail(Long id);

    /**
     * 出题（慢，调模型）。
     * <p>
     * ★ <b>幂等</b>：已有题目会先清后出。所以出题失败（或想换一套题）时
     * 直接再调一次就行，不用删了重建。
     * <p>
     * 输入是简历原文 + JD 原文（都从库里取，不重新解析）。
     * 简历关联了但<b>还没解析</b>会报错 —— 让用户先去解析，
     * 而不是拿一份空简历让模型瞎出题。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         面试不存在（404）/ 已开始作答（409，不允许中途换题）/
     *         简历没解析过（400）/ AI 服务不可用（502、504）
     */
    InterviewDetailVO generate(Long id);

    /**
     * 开始作答（快）。
     * <p>
     * 要求已经出过题 —— 没有题目就「开始」是没有意义的，
     * 返回 409 让前端先调 {@link #generate(Long)}。
     */
    InterviewDetailVO start(Long id);

    /**
     * 提交单题回答（慢，调模型评分）。
     * <p>
     * ⚠️ {@code answer} 允许为空 —— 跳过一题是正常行为，会给低分而不是报错。
     * <p>
     * 返回里同时带上「本题分析」和「下一道未作答的题」，
     * 前端不用再发一次请求拿下一题。
     */
    InterviewAnswerResultVO answer(Long id, InterviewAnswerDTO dto);

    /**
     * 结束面试并生成复盘报告（慢，调模型）。
     * <p>
     * 会把<b>逐题得分和逐题分析一起</b>喂给模型 —— 报告要求「四个分数和逐题得分对得上」，
     * 不给逐题分模型只能瞎给，用户发现「每题都 80 综合却给 60」就不再信任报告。
     * <p>
     * 一道题都没答时会拒绝（没有素材可复盘）。重复调用会覆盖上一次的报告。
     */
    InterviewReportVO finish(Long id);

    /** 取复盘报告。还没结束时返回 404（而不是空对象 —— 那会让前端以为是「报告是空的」） */
    InterviewReportVO getReport(Long id);

    /**
     * 删除面试（逻辑删面试 + 物理删题目和报告）。
     * <p>
     * ⚠️ 题目和报告都没有 {@code deleted} 列，只能物理删 ——
     * 本项目所有表都没有外键，所以必须在同一个事务里手动清干净。
     */
    void delete(Long id);
}
