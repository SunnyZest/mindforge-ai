package com.zest.mindforgeai.service;

import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.RecycleBinVO;

/**
 * 回收站：逻辑删除后的恢复入口。
 *
 * <p>设计要点：<b>删除时登记、恢复时改回</b>。各业务 Service 的 {@code delete}
 * 在逻辑删除之后调一次 {@link #put}，恢复与彻底删除统一收口到这里 ——
 * 避免每个 Service 各写一套「怎么恢复」，加一种资源时只改一处。
 *
 * @author crx
 * @since 2026-10-22
 */
public interface RecycleBinService {

    /**
     * 登记一条删除记录（由各业务 Service 的 delete 调用）。
     *
     * <p>必须在<b>逻辑删除之后</b>调用：先删成功再登记，删失败就不该留下一条
     * 「看起来能恢复、其实原行还在」的脏记录。
     *
     * @param type       资源类型
     * @param resourceId 原表 id
     * @param userId     归属用户
     * @param title      展示名（职位名 / 公司名 / 对话标题）
     */
    void put(RecycleResourceType type, Long resourceId, Long userId, String title);

    /**
     * 最近删除列表（只含本人、未过期的记录）。
     *
     * @param resourceType 可选，按类型筛选；传不认识的值时忽略筛选
     */
    PageResultVO<RecycleBinVO> page(long page, long pageSize, String resourceType);

    /**
     * 恢复。把原表的 {@code deleted} 改回 0，并删掉回收站记录。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         记录不存在 / 不属于本人（404）、已过恢复期或原行已恢复（409）
     */
    void restore(Long id);

    /**
     * 彻底删除（不可逆）：物理删掉原行，再删回收站记录。
     *
     * <p>⚠️ 只删 {@code deleted = 1} 的行 —— 如果期间已经被恢复，
     * 这里必须什么都别做，否则就把正常数据删了。
     */
    void purge(Long id);
}
