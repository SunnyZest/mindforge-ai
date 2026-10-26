package com.zest.mindforgeai.service;

import com.zest.mindforgeai.common.constant.RecycleResourceType;

/**
 * 回收站的「关联数据恢复策略」。
 *
 * <p>★ <b>为什么需要这层抽象</b>：Offer 与面试日程删除时只把 {@code deleted} 置 1，
 * 恢复就是改回 0 —— 它们不需要任何策略。但职位 / 面试 / 对话的关联数据是**物理删**的，
 * 必须靠 {@code payload_json} 里的快照重建。如果把这些重建逻辑全塞进
 * {@code RecycleBinServiceImpl}，那个类就会同时依赖六七个业务的 Mapper，变成一个上帝类。
 *
 * <p>★ <b>职责边界</b>：主表的 {@code deleted = 0} 由 {@code RecycleBinServiceImpl}
 * 统一处理，Handler 只管关联数据 —— 每个实现都很薄，也不会有人漏改主表。
 *
 * @author crx
 * @since 2026-10-26
 */
public interface RecycleRestoreHandler {

    /** 支持哪种资源，必须唯一 */
    RecycleResourceType type();

    /**
     * 删除**之前**采集快照。
     *
     * <p>⚠️ 调用时机很关键：必须在物理删之前。物理删之后再调，查到的只会是空集合。
     *
     * @return 存进 {@code recycle_bin.payload_json} 的 JSON；采集失败返回 {@code null}
     *         （宁可恢复出来残缺，也不要阻断删除本身）
     */
    String capturePayload(Long resourceId);

    /**
     * 恢复关联数据（主表已改回 {@code deleted = 0} 之后调用）。
     *
     * @param payloadJson {@link #capturePayload} 的产物，老数据可能为 {@code null}
     * @return <b>是否完整恢复</b>。{@code false} 表示快照缺失或解析失败、只还原了主记录 ——
     *         调用方要把这个结果透给前端，让用户知道「东西没全回来」
     */
    boolean restoreRelated(Long resourceId, String payloadJson);
}
