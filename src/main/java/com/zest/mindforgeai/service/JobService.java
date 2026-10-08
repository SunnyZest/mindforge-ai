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
    PageResultVO<JobVO> pageJobs(long page, long pageSize, String keyword);

    /** 详情（含 JD 正文和已解析的结构化数据） */
    JobDetailVO getDetail(Long id);

    /** 创建 */
    JobVO create(JobCreateDTO dto);

    /** 修改（null 字段表示不修改） */
    JobVO update(Long id, JobUpdateDTO dto);

    /** 删除（逻辑删除） */
    void delete(Long id);
}
