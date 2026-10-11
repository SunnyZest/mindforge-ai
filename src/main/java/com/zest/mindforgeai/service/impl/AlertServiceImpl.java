package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.mapper.AlertMapper;
import com.zest.mindforgeai.pojo.entity.SysAlert;
import com.zest.mindforgeai.pojo.vo.AlertVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AlertNotifier;
import com.zest.mindforgeai.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 高危操作告警服务实现。
 *
 * @author crx
 * @since 2026-10-11
 */
@Slf4j
@Service
public class AlertServiceImpl extends ServiceImpl<AlertMapper, SysAlert>
        implements AlertService {

    /** 单页上限，对齐审计日志查询 */
    private static final long MAX_PAGE_SIZE = 100;

    /** 允许前端排序的字段（白名单）—— 排序值会被拼进 SQL，白名单是防注入的必需项 */
    private static final Map<String, SFunction<SysAlert, ?>> SORTABLE = Map.of(
            "action", SysAlert::getAction,
            "resourceType", SysAlert::getResourceType,
            "createdAt", SysAlert::getCreatedAt);

    private final AlertNotifier notifier;

    public AlertServiceImpl(AlertNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public void createFromAudit(Long userId, String action, String resourceType, String resourceId,
                                String requestId, boolean success, String detailJson) {
        try {
            SysAlert alert = new SysAlert()
                    .setUserId(userId)
                    .setAction(action)
                    .setResourceType(resourceType)
                    .setResourceId(resourceId)
                    .setRequestId(requestId)
                    .setTitle(buildTitle(action, success))
                    .setDetailJson(detailJson)
                    .setCreatedAt(LocalDateTime.now());
            save(alert);

            notifier.notify(buildNotifyContent(alert));
        } catch (Exception e) {
            // ⚠️ 告警是审计的旁路。审计事务（REQUIRES_NEW）里绝不能被它拖垮，
            //    但也不能静默 —— 这条高危操作就查不到了，要留 ERROR。
            log.error("生成高危操作告警失败 action={} resourceType={} resourceId={}",
                    action, resourceType, resourceId, e);
        }
    }

    @Override
    public PageResultVO<AlertVO> pageAlerts(long page, long pageSize, boolean unreadOnly,
                                            String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<SysAlert> wrapper = new LambdaQueryWrapper<SysAlert>()
                .isNull(unreadOnly, SysAlert::getReadAt);

        // 兜底按 id 倒序：最新的告警排最前，同一秒多条也能稳定排序
        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, SysAlert::getId, false);

        Page<SysAlert> pageResult = page(new Page<>(safePage, safeSize), wrapper);
        return PageResultVO.of(pageResult,
                pageResult.getRecords().stream().map(AlertVO::of).toList());
    }

    @Override
    public long unreadCount() {
        return count(new LambdaQueryWrapper<SysAlert>().isNull(SysAlert::getReadAt));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markRead(long alertId, long adminUserId) {
        // 只更新未读的：已读的再标一次不报错、也不覆盖「谁在什么时候读的」
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<SysAlert>()
                .eq(SysAlert::getId, alertId)
                .isNull(SysAlert::getReadAt)
                .set(SysAlert::getReadBy, adminUserId)
                .set(SysAlert::getReadAt, LocalDateTime.now()));

        if (updated == 0 && getById(alertId) == null) {
            throw new BizException(ErrorCode.ALERT_NOT_FOUND);
        }
        return updated > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(long adminUserId) {
        return baseMapper.update(null, new LambdaUpdateWrapper<SysAlert>()
                .isNull(SysAlert::getReadAt)
                .set(SysAlert::getReadBy, adminUserId)
                .set(SysAlert::getReadAt, LocalDateTime.now()));
    }

    private String buildTitle(String action, boolean success) {
        return "高危操作告警：" + action + (success ? "（成功）" : "（失败）");
    }

    private String buildNotifyContent(SysAlert alert) {
        StringBuilder sb = new StringBuilder("[MindForge] ").append(alert.getTitle()).append('\n')
                .append("操作人 id：").append(alert.getUserId() == null ? "系统" : alert.getUserId()).append('\n');
        if (alert.getResourceType() != null) {
            sb.append("资源：").append(alert.getResourceType())
                    .append(alert.getResourceId() == null ? "" : "/" + alert.getResourceId()).append('\n');
        }
        if (alert.getRequestId() != null) {
            sb.append("traceId：").append(alert.getRequestId()).append('\n');
        }
        sb.append("时间：").append(alert.getCreatedAt());
        return sb.toString();
    }
}
