package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 求职日程（笔试 / 面试 / HR 面等时间节点）。
 * <p>
 * ★ <b>为什么单独一张表，而不是复用 {@code job.next_action_at}</b>：
 * 那个字段只有一个时间点，装不下「周三笔试 + 下周一技术一面 + 下周五 HR 面」这种
 * 同一家公司多场次的真实情况，也没法记录形式（现场/线上）、地点、时长、备注。
 * 日历要的是「一段时间轴上的多个事件」，所以必须是独立实体。
 * <p>
 * 两者的关系：本表是<b>日程的事实来源</b>，{@code job.next_action_at} 是派生出来的
 * 「最近一场未完成日程」，由 {@code JobEventServiceImpl} 在日程增删改后回写 ——
 * 这样看板卡片上的「待办提醒」和日历里看到的永远是同一件事。
 *
 * @author crx
 * @since 2026-10-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("job_event")
@Schema(description = "求职日程")
public class JobEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属用户 id，逻辑关联 user.id")
    private Long userId;

    @Schema(description = "关联职位 id —— 日程一定属于某个职位（宣讲会之类的可以先建个 WISHLIST 职位）")
    private Long jobId;

    @Schema(description = "类型：WRITTEN_TEST 笔试 / INTERVIEW_1 一面 / INTERVIEW_2 二面 / HR HR面 / OFFER_TALK 谈薪 / OTHER 其他")
    private String eventType;

    @Schema(description = "标题（如「字节跳动 技术一面」）")
    private String title;

    @Schema(description = "开始时间")
    private LocalDateTime eventAt;

    @Schema(description = "预计时长（分钟），可空")
    private Integer durationMinutes;

    @Schema(description = "形式：ONSITE 现场 / ONLINE 线上 / PHONE 电话，可空")
    private String eventMode;

    @Schema(description = "地点或会议链接，可空")
    private String location;

    @Schema(description = "备注（面试官、准备要点），可空")
    private String note;

    @Schema(description = "状态：PENDING 待进行 / DONE 已完成 / CANCELLED 已取消")
    private String status;

    /**
     * 已发提醒的时间，{@code null} 表示还没提醒过。
     * <p>
     * ★ 这个字段是<b>提醒幂等的唯一依据</b>，靠「抢占式更新」而不是「定时任务只跑一次」：
     * <pre>UPDATE job_event SET reminded_at = ? WHERE id = ? AND reminded_at IS NULL</pre>
     * 受影响行数 = 1 的那一次才真正发信。多实例部署、任务重跑、运维手动触发，
     * 都不会让用户收到两封一样的提醒。
     * <p>
     * ⚠️ 走自定义 XML 时记得 {@code deleted = 0} 要手写 —— 全局逻辑删除只对
     * MyBatis-Plus 自己拼的 SQL 生效，XML 里的手写 SQL 它管不着。
     */
    @Schema(description = "已发提醒时间，null 表示未提醒")
    private LocalDateTime remindedAt;

    /**
     * 提醒发送失败次数。发信失败时把 {@link #remindedAt} 置回 {@code null} 并 +1，
     * 达到上限后不再重试。
     * <p>
     * ★ 为什么要这个字段：失败重试必须有终点。一个永久失效的邮箱如果无限重试，
     * 每小时都会打一次 SMTP，日志里堆满同一条报错，真正的问题反而被淹掉。
     */
    @Schema(description = "提醒发送失败次数")
    private Integer remindFailCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;

    @Schema(description = "逻辑删除：0 未删除 / 1 已删除")
    private Integer deleted;
}
