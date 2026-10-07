package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.SecurityProperties;
import com.zest.mindforgeai.service.LoginAttemptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * 登录失败限制实现 —— 用 Redis 计数。
 * <p>
 * <b>为什么不用单独的「锁定状态」字段：</b>
 * 失败次数本身就能表达锁定 —— 计数达到阈值即为锁定，
 * 而 key 的 TTL 到期后计数自动消失，也就自动解锁了。
 * 少维护一份状态，就少一处不一致的可能。
 *
 * @author crx
 * @since 2026-10-07
 */
@Slf4j
@Service
public class LoginAttemptServiceImpl implements LoginAttemptService {

    /** key 前缀，方便在 Redis 里辨认，也避免和其他业务冲突 */
    private static final String PREFIX_USER = "mf:login:fail:u:";
    private static final String PREFIX_IP = "mf:login:fail:ip:";

    private final SecurityProperties properties;
    private final StringRedisTemplate redis;

    public LoginAttemptServiceImpl(SecurityProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
    }

    @Override
    public void checkNotLocked(String username, String clientIp) {
        if (!properties.getLoginAttempt().isEnabled()) {
            return;
        }

        SecurityProperties.LoginAttempt cfg = properties.getLoginAttempt();
        long userRemain = lockedRemainSeconds(PREFIX_USER + normalize(username), cfg.getMaxPerUsername());
        long ipRemain = lockedRemainSeconds(PREFIX_IP + clientIp, cfg.getMaxPerIp());
        long remain = Math.max(userRemain, ipRemain);

        if (remain > 0) {
            long minutes = (remain + 59) / 60;          // 向上取整，别显示「0 分钟」
            log.warn("登录被拒：账号或 IP 处于锁定中，剩余 {} 分钟（username={} ip={}）",
                    minutes, username, clientIp);
            throw new BizException(ErrorCode.LOGIN_TOO_MANY_ATTEMPTS,
                    String.format("登录失败次数过多，请 %d 分钟后再试", minutes));
        }
    }

    @Override
    public void recordFailure(String username, String clientIp) {
        if (!properties.getLoginAttempt().isEnabled()) {
            return;
        }

        SecurityProperties.LoginAttempt cfg = properties.getLoginAttempt();
        int userCount = increment(PREFIX_USER + normalize(username), cfg.getLockMinutes());
        int ipCount = increment(PREFIX_IP + clientIp, cfg.getLockMinutes());

        // 只在「刚好达到阈值」那一次打日志，避免每次失败都刷屏
        if (userCount == cfg.getMaxPerUsername()) {
            log.warn("账号 {} 连续登录失败 {} 次，已锁定 {} 分钟",
                    username, userCount, cfg.getLockMinutes());
        }
        if (ipCount == cfg.getMaxPerIp()) {
            log.warn("IP {} 连续登录失败 {} 次，已锁定 {} 分钟",
                    clientIp, ipCount, cfg.getLockMinutes());
        }
    }

    @Override
    public void recordSuccess(String username, String clientIp) {
        if (!properties.getLoginAttempt().isEnabled()) {
            return;
        }
        // 只清账号 —— IP 计数留着，防止「攻击者自己登录成功一次就把额度重置」
        redis.delete(PREFIX_USER + normalize(username));
    }

    // ---------- 内部 ----------

    /**
     * 计数 +1 并保证有 TTL，返回加完之后的次数。
     * <p>
     * 只有第一次（返回 1）时设过期 —— 后续失败不再续期，
     * 否则攻击者一直尝试，key 永远不过期，用户就永远解不开锁了。
     */
    private int increment(String key, int lockMinutes) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return 0;
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofMinutes(lockMinutes));
        }
        return count.intValue();
    }

    /**
     * 如果该 key 的计数已达阈值，返回剩余锁定秒数；否则返回 0。
     */
    private long lockedRemainSeconds(String key, int threshold) {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        int count;
        try {
            count = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // 值被人为改坏了，清掉重来
            log.warn("登录失败计数不是数字（key={} value={}），已清除", key, value);
            redis.delete(key);
            return 0;
        }
        if (count < threshold) {
            return 0;
        }

        Long ttl = redis.getExpire(key);
        // TTL 取不到（-1 没设过期 / -2 key 不存在）时，按配置的锁定时长兜底
        return (ttl == null || ttl <= 0) ? properties.getLoginAttempt().getLockMinutes() * 60L : ttl;
    }

    /**
     * 用户名统一转小写 —— 否则攻击者用 {@code Admin} / {@code ADMIN} / {@code aDmin}
     * 轮着试，每次都被当成不同的 key，计数永远到不了阈值。
     */
    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
