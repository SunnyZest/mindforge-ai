package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.JobEventSaveDTO;
import com.zest.mindforgeai.pojo.vo.JobEventVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 求职日程（面试日历）。
 *
 * @author crx
 * @since 2026-10-16
 */
public interface JobEventService {

    /**
     * 按时间范围查日程。
     *
     * @param from 起始日期（含当天 00:00），不传则取当前月 1 号
     * @param to   结束日期（含当天 23:59:59），不传则取当前月最后一天
     */
    List<JobEventVO> listByRange(LocalDate from, LocalDate to);

    /** 新建日程 */
    JobEventVO create(JobEventSaveDTO dto);

    /** 修改日程（整体覆盖） */
    JobEventVO update(Long id, JobEventSaveDTO dto);

    /** 删除日程（逻辑删除） */
    void delete(Long id);
}
