package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.SysAlert;
import com.zest.mindforgeai.pojo.vo.AlertVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * 高危操作告警服务。
 * <p>
 * 生成入口只有一个：审计写入处发现 action 是高危操作时回调 {@link #createFromAudit}。
 * 告警是审计的旁路 —— 生成失败只记日志，绝不影响审计、更不影响业务。
 *
 * @author crx
 * @since 2026-10-11
 */
public interface AlertService extends IService<SysAlert> {

    /**
     * 由审计写入处回调：一条高危审计对应一条告警。
     * <p>
     * 内部吞掉一切异常（含 webhook 外推失败）—— 审计事务里绝不能被告警拖垮。
     *
     * @param userId       操作人（可空 = 系统触发）
     * @param action       高危动作，已在审计处判定过属于高危清单
     * @param resourceType 资源类型，可空
     * @param resourceId   资源 id，可空
     * @param requestId    触发请求的 traceId，可空
     * @param success      操作是否成功（失败的更可疑，标题里带结果）
     * @param detailJson   审计的明细 JSON，原样透传，可空
     */
    void createFromAudit(Long userId, String action, String resourceType, String resourceId,
                         String requestId, boolean success, String detailJson);

    /**
     * 分页查询告警，供管理端 {@code admin:alert:list} 使用。
     *
     * @param unreadOnly 只看未读
     * @param sortBy     排序字段（白名单），可空
     * @param sortOrder  asc / desc，可空
     */
    PageResultVO<AlertVO> pageAlerts(long page, long pageSize, boolean unreadOnly,
                                     String sortBy, String sortOrder);

    /** 未读数量。管理端首页 / 告警页的角标用它，一次 COUNT 而已。 */
    long unreadCount();

    /**
     * 标记单条已读。幂等：已读的再标一次不报错、不覆盖原来的已读信息。
     *
     * @return false = 告警不存在
     */
    boolean markRead(long alertId, long adminUserId);

    /** 全部标记已读，返回本次新标记的条数。 */
    int markAllRead(long adminUserId);
}
