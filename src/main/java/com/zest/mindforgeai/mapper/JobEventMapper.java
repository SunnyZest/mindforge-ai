package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 求职日程 Mapper。
 * <p>
 * 除通用的增删改查外，提醒相关的四条 SQL 走 XML（{@code mapper/JobEventMapper.xml}）。
 * <p>
 * ⚠️ <b>XML 里的手写 SQL 不会自动带上逻辑删除条件</b>：全局 {@code logic-delete-field: deleted}
 * 只对 MyBatis-Plus 自己拼的 SQL 生效，所以下面每条语句都手写了 {@code deleted = 0}。
 * 漏写的后果不是报错，而是<b>把用户已经删掉的日程也提醒一遍</b>。
 *
 * @author crx
 * @since 2026-10-16
 */
@Mapper
public interface JobEventMapper extends BaseMapper<JobEvent> {

    /**
     * 找出「未来窗口内有待办日程、且够资格收邮件」的用户 id。
     * <p>
     * 资格判定放在 SQL 里一次做完，不先捞出日程再逐个查用户 ——
     * 每小时跑一次的任务，能少一轮往返就少一轮。
     *
     * @param from    窗口起点（通常是当前时间，已经开始的日程不再提醒）
     * @param to      窗口终点（通常是当前时间 + {@code aheadHours}）
     * @param maxFail 失败次数上限，达到的用户直接排除
     * @return 去重后的用户 id
     */
    List<Long> selectRemindableUserIds(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("maxFail") int maxFail);

    /**
     * 抢占某个用户本窗口内所有待提醒的日程。
     * <p>
     * ★ <b>整个提醒功能的幂等就靠这一条 SQL</b>。它把「判断该不该发」和「标记已经发了」
     * 压成一次原子更新：受影响行数 = 0 说明别人抢走了（或本来就没有），此时绝不能发信。
     * <p>
     * 不用「先查后改」的原因：两个实例同时查到同一条待提醒日程、各自发一封，
     * 用户就会收到两封一模一样的邮件 —— 这是分布式下最典型的重复推送。
     *
     * @param userId  用户 id
     * @param from    窗口起点
     * @param to      窗口终点
     * @param now     写入 {@code reminded_at} 的时间戳，调用方需<b>截断到秒</b>，
     *                否则数据库 datetime 的截断会让后续按它反查时对不上
     * @param maxFail 失败次数上限
     * @return 受影响行数，1 及以上表示抢到
     */
    int claimReminder(@Param("userId") Long userId,
                      @Param("from") LocalDateTime from,
                      @Param("to") LocalDateTime to,
                      @Param("now") LocalDateTime now,
                      @Param("maxFail") int maxFail);

    /**
     * 取回刚被抢占的那批日程，用于拼邮件正文。
     * <p>
     * 用 {@code reminded_at = 抢占时间戳} 而不是「再查一遍窗口」：
     * 抢占和查询之间如果用户新建了一条日程，重查窗口会把它算进来 ——
     * 于是它被写进邮件却没被标记，下一小时又提醒一次。
     *
     * @param userId     用户 id
     * @param remindedAt 抢占时写入的时间戳
     */
    List<JobEvent> selectClaimedEvents(@Param("userId") Long userId,
                                       @Param("remindedAt") LocalDateTime remindedAt);

    /**
     * 发信失败时回滚抢占：把 {@code reminded_at} 置回 NULL，失败次数 +1。
     * <p>
     * 失败次数到上限后，{@link #selectRemindableUserIds} 会自然把它排除，
     * 不需要额外的「放弃」标记位。
     *
     * @param ids 需要回滚的日程 id，不能为空
     */
    int resetReminder(@Param("ids") List<Long> ids);
}
