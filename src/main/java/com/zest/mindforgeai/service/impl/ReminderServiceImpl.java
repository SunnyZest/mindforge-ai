package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.properties.ReminderProperties;
import com.zest.mindforgeai.mapper.JobEventMapper;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.service.ReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 日程主动提醒实现。
 * <p>
 * 整体是「先抢占、再发信、失败回滚」三步，全部围绕 {@code job_event.reminded_at} 展开。
 *
 * <p>★ <b>这个方法刻意不加 {@code @Transactional}</b>，改之前先看完这段：
 * <ol>
 *   <li>抢占必须<b>立即提交</b>。如果整轮包在一个事务里，抢占拿到的行锁会一直握到
 *       所有邮件发完 —— SMTP 单封超时是 5 秒，几十个用户就是几分钟的长事务，
 *       期间任何一条日程的新增/修改都会被阻塞。</li>
 *   <li>更糟的是：中间任何一个用户发信失败抛异常，整个事务回滚，
 *       前面已经<b>真的发出去</b>的邮件对应的 {@code reminded_at} 也一起被撤销 ——
 *       下一轮会再发一遍，用户收到重复邮件。</li>
 * </ol>
 * 所以这里的正确性来自「每条 UPDATE 自己是一个原子操作」，不来自事务边界。
 *
 * @author crx
 * @since 2026-10-28
 */
@Slf4j
@Service
public class ReminderServiceImpl implements ReminderService {

    private final JobEventMapper jobEventMapper;
    private final UserMapper userMapper;
    private final ReminderMailSender mailSender;
    private final ReminderProperties properties;

    public ReminderServiceImpl(JobEventMapper jobEventMapper,
                               UserMapper userMapper,
                               ReminderMailSender mailSender,
                               ReminderProperties properties) {
        this.jobEventMapper = jobEventMapper;
        this.userMapper = userMapper;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public boolean isMailChannelReady() {
        return mailSender.isConfigured();
    }

    @Override
    public int remindUpcomingEvents() {
        if (!mailSender.isConfigured()) {
            log.warn("日程提醒跳过：邮件通道未配置（spring.mail.username 为空）");
            return 0;
        }

        // ⚠️ 必须截断到秒。reminded_at 是 datetime(0)，带纳秒写进去会被库截断，
        //    后面按这个时间戳反查就一条都查不到 —— 表现为「抢到了但邮件正文是空的」。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime to = now.plusHours(properties.getAheadHours());

        List<Long> userIds = jobEventMapper.selectRemindableUserIds(now, to, properties.getMaxFail());
        if (userIds.isEmpty()) {
            log.debug("日程提醒：窗口 {} ~ {} 内没有待提醒的日程", now, to);
            return 0;
        }

        int sent = 0;
        int lostRace = 0;
        int failed = 0;

        for (Long userId : userIds) {
            // 抢占：受影响行数 = 0 说明别的实例先抢到了（或这批刚被用户改成 DONE / 取消），直接放过
            int claimed = jobEventMapper.claimReminder(userId, now, to, now, properties.getMaxFail());
            if (claimed == 0) {
                lostRace++;
                continue;
            }

            List<JobEvent> events = jobEventMapper.selectClaimedEvents(userId, now);
            if (events.isEmpty()) {
                // 抢到了却查不到，只可能是时间戳精度问题；记 WARN 便于以后排查
                log.warn("日程提醒：抢占成功但按时间戳查不到日程 userId={} remindedAt={}", userId, now);
                continue;
            }

            try {
                User user = userMapper.selectById(userId);
                if (user == null) {
                    // 抢占和发信之间账号被删了。回滚没有意义（这行数据马上会被清理），
                    // 但也不能留在「已提醒」状态里装作发过了
                    log.info("日程提醒跳过：用户已不存在 userId={}", userId);
                    rollback(events);
                    continue;
                }
                mailSender.sendReminder(user, events);
                sent++;
            } catch (Exception e) {
                // ★ 单个用户失败不能中断整轮：一个打错的邮箱不该让后面几十个人都收不到提醒
                failed++;
                rollback(events);
                log.error("日程提醒发送失败 userId={} 条数={}，已回滚等待重试",
                        userId, events.size(), e);
            }
        }

        log.info("日程提醒完成 候选用户={} 已发送={} 抢占失败={} 发送失败={}",
                userIds.size(), sent, lostRace, failed);
        return sent;
    }

    /**
     * 回滚抢占：把 {@code reminded_at} 置回 NULL 并把失败次数 +1。
     * <p>
     * 失败次数到上限后，下一轮的扫描 SQL 会自然把这些日程排除，不需要额外的「放弃」标记。
     */
    private void rollback(List<JobEvent> events) {
        List<Long> ids = events.stream().map(JobEvent::getId).toList();
        try {
            jobEventMapper.resetReminder(ids);
        } catch (Exception e) {
            // 回滚都失败了，只能靠日志兜底：这批日程会一直停在「已提醒」状态，不会再发
            log.error("日程提醒回滚失败，这批日程将不会重试 ids={}", ids, e);
        }
    }
}
