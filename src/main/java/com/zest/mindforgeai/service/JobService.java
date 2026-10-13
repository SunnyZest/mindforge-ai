package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.JobCreateDTO;
import com.zest.mindforgeai.pojo.dto.JobUpdateDTO;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.JobVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * 职位 JD 服务（用户端）。
 * <p>
 * <b>所有方法都只操作当前登录用户自己的职位</b> —— 列表查询直接带 user_id 条件，
 * 按 id 操作的一律先走归属校验。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface JobService {

    /** 分页列表（关键词匹配职位名称 / 公司名称） */
    PageResultVO<JobVO> pageJobs(long page, long pageSize, String keyword,
                                 String sortBy, String sortOrder);

    /** 详情（含 JD 正文和已解析的结构化数据） */
    JobDetailVO getDetail(Long id);

    /** 创建 */
    JobVO create(JobCreateDTO dto);

    /** 修改（null 字段表示不修改） */
    JobVO update(Long id, JobUpdateDTO dto);

    /** 删除（逻辑删除） */
    void delete(Long id);

    /**
     * 解析 JD（同步）：技能 / 经验 / 学历 / 职责。
     * <p>
     * 输入就是 {@code job.raw_content}（用户粘贴的那段 JD），不用下载文件 ——
     * 这是它和简历解析最大的不同。
     * <p>
     * 结果落 {@code structured_json}，技能重写进 {@code job_skill}，
     * 并按 AI 提取的值更新 {@code experience_required} / {@code education_required}
     * （这两个是<b>派生数据</b>，以 JD 正文为准）。
     * <p>
     * ⚠️ 慢接口（要等模型生成），期间 {@code parse_status} 是 {@code PARSING}。
     * 失败时置 {@code FAILED} + 写 {@code error_message}，并把异常抛出去。
     * <p>
     * ⚠️ <b>失败【不清空】已有的解析结果</b>。所以
     * {@code structuredJson} 是「最近一次成功」的结果、
     * {@code parseStatus} 是「最近一次尝试」的结果，两者可能不一致
     * （正文没变时旧结果依然有效，不该因为一次重试失败就删掉）。
     * 详细说明见 {@code ResumeService#parse}。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         职位不存在 / 没有 JD 正文 / 正在解析中（40902）/ AI 服务不可用
     */
    JobDetailVO parse(Long id);

    /** 获取求职投递看板各阶段分列卡片数据 */
    java.util.List<com.zest.mindforgeai.pojo.vo.JobKanbanColumnVO> getKanbanBoard(String keyword);

    /** 获取求职投递看板统计数据概览 */
    com.zest.mindforgeai.pojo.vo.JobKanbanStatsVO getKanbanStats();

    /** 快速拖拽/变更职位投递阶段 */
    com.zest.mindforgeai.pojo.vo.JobKanbanCardVO updateStage(Long id, com.zest.mindforgeai.pojo.dto.JobStageUpdateDTO dto);

    /** 更新职位求职 CRM 跟进信息（投递阶段、绑定简历、薪资包、面试日程提醒、跟进备注） */
    com.zest.mindforgeai.pojo.vo.JobKanbanCardVO updateCrm(Long id, com.zest.mindforgeai.pojo.dto.JobCrmUpdateDTO dto);
}
