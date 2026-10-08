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

    /**
     * <b>Redis 不可用时的降级：放行（fail-open）。</b>
     * <p>
     * 权衡过的选择：
     * <ul>
     *   <li>fail-closed（拒绝登录）→ Redis 一挂，<b>所有人都登不进来</b>，
     *       把一个「计数组件故障」放大成「全站不可用」</li>
     *   <li>fail-open（放行）→ 登录限制暂时失效，可能被爆破，
     *       但服务还是可用的，而且有 ERROR 日志能立刻发现</li>
     * </ul>
     * 可用性优先。代价是 Redis 故障期间防护变弱 ——
     * 所以这里必须打 ERROR，让运维尽快恢复 Redis。
     * <p>
     * ⚠️ 别把这里的 try-catch 去掉：实测踩过，
     * Redis 没起来时登录接口直接 500，而且错误信息是「系统内部异常」，
     * 排查时会往错误的方向找。
     */
    @Override
    public void checkNotLocked(String username, String clientIp) {
        if (!properties.getLoginAttempt().isEnabled()) {
            return;
        }

        try {
            SecurityProperties.LoginAttempt cfg = properties.getLoginAttempt();
            long userRemain = lockedRemainSeconds(PREFIX_USER + normalize(username), cfg.getMaxPerUsername());
            long ipRemain = lockedRemainSeconds(PREFIX_IP + clientIp, cfg.getMaxPerIp());
            long remain = Math.max(userRemain, ipRemain);

            if (remain > 0) {
                long minutes = (remain + 59) / 60;      // 向上取整，别显示「0 分钟」
                log.warn("登录被拒：账号或 IP 处于锁定中，剩余 {} 分钟（username={} ip={}）",
                        minutes, username, clientIp);
                throw new BizException(ErrorCode.LOGIN_TOO_MANY_ATTEMPTS,
                        String.format("登录失败次数过多，请 %d 分钟后再试", minutes));
            }
        } catch (BizException e) {
            throw e;                                    // 业务异常照常往上抛
        } catch (Exception e) {
            log.error("检查登录锁定状态失败（Redis 不可用？），本次放行 —— "
                    + "此时登录失败限制形同虚设，请尽快恢复 Redis", e);
        }
    }

    @Override
    public void recordFailure(String username, String clientIp) {
        if (!properties.getLoginAttempt().isEnabled()) {
            return;
        }

        try {
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
        } catch (Exception e) {
            // 计数失败不影响「本次登录失败」这个事实的返回，只记日志
            log.error("记录登录失败次数失败（Redis 不可用？）username={}", username, e);
        }
    }

    @Override
    public void recordSuccess(String username, String clientIp) {
        if (!properties.getLoginAttempt().isEnabled()) {
            return;
        }
        try {
            // 只清账号 —— IP 计数留着，防止「攻击者自己登录成功一次就把额度重置」
            redis.delete(PREFIX_USER + normalize(username));
        } catch (Exception e) {
            log.error("清除登录失败计数失败（Redis 不可用？）username={}", username, e);
        }
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
