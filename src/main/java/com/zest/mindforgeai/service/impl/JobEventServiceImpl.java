package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.utils.SecurityUtils;
import com.zest.mindforgeai.mapper.JobEventMapper;
import com.zest.mindforgeai.mapper.JobMapper;
import com.zest.mindforgeai.pojo.dto.JobEventSaveDTO;
import com.zest.mindforgeai.pojo.entity.Job;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import com.zest.mindforgeai.pojo.vo.JobEventVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.JobEventService;
import com.zest.mindforgeai.service.RecycleBinService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 求职日程实现。
 *
 * @author crx
 * @since 2026-10-16
 */
@Slf4j
@Service
public class JobEventServiceImpl extends ServiceImpl<JobEventMapper, JobEvent> implements JobEventService {

    /** 待进行 */
    private static final String STATUS_PENDING = "PENDING";

    /** 单次查询的最大跨度 —— 日历一次只看一个月，给 366 天已经非常宽松，
     *  再大就是拿接口当全量导出用了。 */
    private static final int MAX_RANGE_DAYS = 366;

    private final JobMapper jobMapper;
    private final AuditService auditService;
    private final RecycleBinService recycleBinService;

    public JobEventServiceImpl(JobMapper jobMapper, AuditService auditService,
                               RecycleBinService recycleBinService) {
        this.jobMapper = jobMapper;
        this.auditService = auditService;
        this.recycleBinService = recycleBinService;
    }

    @Override
    public List<JobEventVO> listByRange(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today.withDayOfMonth(today.lengthOfMonth());

        if (start.isAfter(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "开始日期不能晚于结束日期");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "查询跨度最长 " + MAX_RANGE_DAYS + " 天");
        }

        List<JobEvent> events = list(new LambdaQueryWrapper<JobEvent>()
                .eq(JobEvent::getUserId, SecurityUtils.getUserId())
                .ge(JobEvent::getEventAt, start.atStartOfDay())
                .lt(JobEvent::getEventAt, end.plusDays(1).atStartOfDay())
                .orderByAsc(JobEvent::getEventAt));

        return toVOs(events);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobEventVO create(JobEventSaveDTO dto) {
        Long jobId = resolveOwnedJobId(dto.getJobId());

        JobEvent event = new JobEvent()
                .setUserId(SecurityUtils.getUserId())
                .setJobId(jobId)
                .setEventType(dto.getEventType().trim().toUpperCase())
                .setTitle(dto.getTitle().trim())
                .setEventAt(dto.getEventAt())
                .setDurationMinutes(dto.getDurationMinutes())
                .setEventMode(normalize(dto.getEventMode()))
                .setLocation(trimToNull(dto.getLocation()))
                .setNote(trimToNull(dto.getNote()))
                .setStatus(StringUtils.hasText(dto.getStatus())
                        ? dto.getStatus().trim().toUpperCase()
                        : STATUS_PENDING);
        save(event);

        syncNextActionAt(jobId);

        auditService.recordSuccess(AuditConstants.ACTION_JOB_EVENT_CREATE,
                AuditConstants.RESOURCE_JOB_EVENT, String.valueOf(event.getId()),
                Map.of("jobId", String.valueOf(jobId), "type", event.getEventType()));

        return toVO(event, jobMapper.selectById(jobId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobEventVO update(Long id, JobEventSaveDTO dto) {
        JobEvent event = requireOwned(id);
        Long oldJobId = event.getJobId();
        Long jobId = resolveOwnedJobId(dto.getJobId());

        event.setJobId(jobId)
                .setEventType(dto.getEventType().trim().toUpperCase())
                .setTitle(dto.getTitle().trim())
                .setEventAt(dto.getEventAt())
                .setDurationMinutes(dto.getDurationMinutes())
                .setEventMode(normalize(dto.getEventMode()))
                .setLocation(trimToNull(dto.getLocation()))
                .setNote(trimToNull(dto.getNote()))
                .setStatus(StringUtils.hasText(dto.getStatus())
                        ? dto.getStatus().trim().toUpperCase()
                        : STATUS_PENDING);
        updateById(event);

        // 改过归属职位的话，两个职位的最早日程都可能变了
        syncNextActionAt(jobId);
        if (!Objects.equals(oldJobId, jobId)) {
            syncNextActionAt(oldJobId);
        }

        auditService.recordSuccess(AuditConstants.ACTION_JOB_EVENT_UPDATE,
                AuditConstants.RESOURCE_JOB_EVENT, String.valueOf(id),
                Map.of("jobId", String.valueOf(jobId), "type", event.getEventType()));

        return toVO(event, jobMapper.selectById(jobId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        JobEvent event = requireOwned(id);
        LocalDateTime removedAt = event.getEventAt();
        removeById(id);

        // 进回收站：日程是纯逻辑删除，恢复时把 deleted 改回 0 就完整了。
        // ⚠️ 但恢复不会把 job.next_action_at 写回来 —— 那是派生值，由后续的日程变更
        //    重新计算（和「删日程只在提醒正好指向它时才清空」是同一套逻辑）。
        recycleBinService.put(RecycleResourceType.JOB_EVENT, id,
                SecurityUtils.getUserId(), event.getTitle());

        syncNextActionAt(event.getJobId(), removedAt);

        auditService.recordSuccess(AuditConstants.ACTION_JOB_EVENT_DELETE,
                AuditConstants.RESOURCE_JOB_EVENT, String.valueOf(id),
                Map.of("jobId", String.valueOf(event.getJobId()), "title", event.getTitle()));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 校验日程归属。
     * <p>
     * 越权一律抛「不存在」而不是 403 —— 抛 403 等于告诉调用方「这个 id 是存在的，
     * 只是不归你」，相当于帮他确认了别人的数据范围。
     */
    private JobEvent requireOwned(Long id) {
        JobEvent event = getById(id);
        if (event == null || !event.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.JOB_EVENT_NOT_FOUND);
        }
        return event;
    }

    /** 校验职位归属并返回其 id —— 日程必须挂在自己名下的职位上 */
    private Long resolveOwnedJobId(String rawJobId) {
        long jobId;
        try {
            jobId = Long.parseLong(rawJobId.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法的职位 ID");
        }
        Job job = jobMapper.selectById(jobId);
        if (job == null || !job.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.JOB_NOT_FOUND);
        }
        return jobId;
    }

    /**
     * 把职位的「待办提醒」回写成最近一场未完成日程。
     * <p>
     * ★ 为什么要有这一步：看板卡片上的「待办提醒」和日历里看到的必须是同一件事，
     * 否则会出现「日历里明明有面试，卡片上却空着」的矛盾观感。
     * <p>
     * ⚠️ 三条边界：
     * ① 只认<b>今天起</b>的 PENDING 日程 —— 几天前忘了标记完成的旧日程不该继续当提醒；
     * ② 还有未来日程 → 回写成最早那场；
     * ③ 一场未来日程都没有 → 只有在「提醒正好指向刚删掉的那场」时才清空。
     *    直接清空会误删用户手填的提醒；一律不清又会留下指向已删日程的僵尸提醒。
     *    比对时间戳能同时避开这两个坑。
     *
     * @param removedAt 本次被删除日程的开始时间；新建/修改时传 null
     */
    private void syncNextActionAt(Long jobId) {
        syncNextActionAt(jobId, null);
    }

    private void syncNextActionAt(Long jobId, LocalDateTime removedAt) {
        if (jobId == null) {
            return;
        }
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }

        List<JobEvent> upcoming = list(new LambdaQueryWrapper<JobEvent>()
                .eq(JobEvent::getJobId, jobId)
                .eq(JobEvent::getStatus, STATUS_PENDING)
                .ge(JobEvent::getEventAt, LocalDate.now().atStartOfDay())
                .orderByAsc(JobEvent::getEventAt));

        if (upcoming.isEmpty()) {
            if (removedAt != null && removedAt.equals(job.getNextActionAt())) {
                // ⚠️ 必须用 LambdaUpdateWrapper 显式 set(null)：
                //    MP 的 updateById 默认【忽略 null 字段】，写成 job.setNextActionAt(null)
                //    + updateById 的话，这条 UPDATE 里压根不会出现 next_action_at，清不掉。
                jobMapper.update(null, new LambdaUpdateWrapper<Job>()
                        .eq(Job::getId, jobId)
                        .set(Job::getNextActionAt, null));
                log.info("职位 {} 的最后一场日程已删除，待办提醒随之清空", jobId);
            }
            return;
        }

        LocalDateTime earliest = upcoming.get(0).getEventAt();
        if (!earliest.equals(job.getNextActionAt())) {
            job.setNextActionAt(earliest);
            jobMapper.updateById(job);
            log.info("职位 {} 的待办提醒回写为最近一场日程 {}", jobId, earliest);
        }
    }

    private List<JobEventVO> toVOs(List<JobEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        Set<Long> jobIds = events.stream()
                .map(JobEvent::getJobId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Job> jobMap = new HashMap<>();
        if (!jobIds.isEmpty()) {
            // 职位可能已被删（逻辑删），这里查不到就退化成 null，日历仍能显示日程本身
            for (Job job : jobMapper.selectList(new LambdaQueryWrapper<Job>().in(Job::getId, jobIds))) {
                jobMap.put(job.getId(), job);
            }
        }

        return events.stream()
                .map(e -> toVO(e, jobMap.get(e.getJobId())))
                .toList();
    }

    private JobEventVO toVO(JobEvent event, Job job) {
        JobEventVO vo = new JobEventVO();
        vo.setId(String.valueOf(event.getId()));
        vo.setJobId(event.getJobId() == null ? null : String.valueOf(event.getJobId()));
        vo.setEventType(event.getEventType());
        vo.setTitle(event.getTitle());
        vo.setEventAt(event.getEventAt());
        vo.setDurationMinutes(event.getDurationMinutes());
        vo.setEventMode(event.getEventMode());
        vo.setLocation(event.getLocation());
        vo.setNote(event.getNote());
        vo.setStatus(event.getStatus());
        vo.setCreatedAt(event.getCreatedAt());
        vo.setUpdatedAt(event.getUpdatedAt());
        if (event.getDurationMinutes() != null && event.getEventAt() != null) {
            vo.setEndAt(event.getEventAt().plusMinutes(event.getDurationMinutes()));
        }
        if (job != null) {
            vo.setJobTitle(job.getTitle());
            vo.setCompanyName(job.getCompanyName());
            vo.setApplicationStage(job.getApplicationStage());
        }
        return vo;
    }

    /** 空字符串统一成 null —— 前端清空输入框会传 ""，直接存进去会变成脏数据 */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : null;
    }
}
