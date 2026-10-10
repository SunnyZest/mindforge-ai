package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoginAttemptServiceImpl} 的单元测试。
 * <p>
 * <b>为什么用纯单元测试（不起 Spring 上下文）</b>：这里验证的两件事在 HTTP 层都测不了 ——
 * <ul>
 *   <li><b>用户名截断</b>：{@code LoginDTO} 上的 {@code @Size(max = 32)} 已经挡在入口，
 *       走接口永远传不进超长用户名 —— 那段兜底代码在 HTTP 层是「不可达」的，
 *       只能直接调 service 才验得到。</li>
 *   <li><b>锁定判定</b>：触发锁定需要连续 6 次登录失败，
 *       而认证路径限流只有 10 次/分钟 —— 实测第 6 次会先撞上限流（42901），
 *       拿不到 42902。这也是 2026-10-10 第一次跑端到端测试时那条断言失败的原因。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-10
 */
class LoginAttemptServiceImplTest {

    private static final String PREFIX_USER = "mf:login:fail:u:";
    private static final String PREFIX_IP = "mf:login:fail:ip:";
    private static final String IP = "1.2.3.4";

    /** 对齐 user.username varchar(32)：16 字节前缀 + 32 = 48 */
    private static final int MAX_USER_KEY_LEN = 48;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginAttemptServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new LoginAttemptServiceImpl(new SecurityProperties(), redis);
    }

    /**
     * ★ 这条是本次修复的核心：超长用户名不能造出超长 Redis key。
     * <p>
     * 修之前实测 2000 字符的用户名会生成 2016 字节的 key、TTL 15 分钟，
     * 攻击者换个用户名就换一个 key —— 等于一个内存放大面。
     */
    @Test
    @DisplayName("超长用户名被截断到 32 字符，key 不会超过 48 字节")
    void recordFailureTruncatesOverlongUsername() {
        service.recordFailure("x".repeat(2000), IP);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        // recordFailure 会写两个 key：账号维度 + IP 维度
        verify(ops, times(2)).increment(keys.capture());

        List<String> captured = keys.getAllValues();
        assertThat(captured.get(0)).isEqualTo(PREFIX_USER + "x".repeat(32));
        assertThat(captured.get(0).length()).isLessThanOrEqualTo(MAX_USER_KEY_LEN);
    }

    /** 否则攻击者用 Admin / ADMIN / aDmin 轮着试，每次都是新 key，计数永远到不了阈值 */
    @Test
    @DisplayName("用户名转小写并去空格")
    void normalizeLowercasesAndTrims() {
        service.recordFailure("  AdMiN  ", IP);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops, times(2)).increment(keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(PREFIX_USER + "admin");
    }

    /** 阈值是 max-per-username（默认 5）：达到就锁定，且提示里要给出剩余分钟数 */
    @Test
    @DisplayName("账号计数达到阈值 → 抛 42902")
    void checkNotLockedThrowsWhenThresholdReached() {
        when(ops.get(PREFIX_USER + "lockeduser")).thenReturn("5");
        when(redis.getExpire(PREFIX_USER + "lockeduser")).thenReturn(900L);

        BizException e = assertThrows(BizException.class,
                () -> service.checkNotLocked("lockeduser", IP));

        assertThat(e.getCode()).isEqualTo(ErrorCode.LOGIN_TOO_MANY_ATTEMPTS.getCode());
        assertThat(e.getMessage()).contains("分钟");
    }

    @Test
    @DisplayName("计数没到阈值 → 放行")
    void checkNotLockedPassesBelowThreshold() {
        when(ops.get(PREFIX_USER + "zhangsan")).thenReturn("4");

        service.checkNotLocked("zhangsan", IP);      // 不抛异常即通过
    }

    /** 账号没锁但 IP 锁了，也要拦 —— 防「换账号接着撞库」 */
    @Test
    @DisplayName("只有 IP 达到阈值 → 同样抛 42902")
    void checkNotLockedThrowsWhenIpThresholdReached() {
        when(ops.get(PREFIX_USER + "zhangsan")).thenReturn(null);
        when(ops.get(PREFIX_IP + IP)).thenReturn("20");
        when(redis.getExpire(PREFIX_IP + IP)).thenReturn(600L);

        BizException e = assertThrows(BizException.class,
                () -> service.checkNotLocked("zhangsan", IP));
        assertThat(e.getCode()).isEqualTo(ErrorCode.LOGIN_TOO_MANY_ATTEMPTS.getCode());
    }

    /**
     * fail-open 是刻意选的：Redis 挂了如果 fail-closed，<b>所有人都登不进来</b> ——
     * 把一个「计数组件故障」放大成「全站不可用」。
     */
    @Test
    @DisplayName("Redis 抛异常 → fail-open，不阻断登录")
    void checkNotLockedFailsOpenOnRedisError() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        service.checkNotLocked("zhangsan", IP);      // 不抛异常即通过
    }

    /** 只清账号不清 IP —— 否则「攻击者自己登录成功一次就把 IP 额度重置了」 */
    @Test
    @DisplayName("登录成功只清账号计数，不动 IP 计数")
    void recordSuccessOnlyClearsUserKey() {
        service.recordSuccess("ZhangSan", IP);

        verify(redis).delete(PREFIX_USER + "zhangsan");
        verify(redis, times(0)).delete(PREFIX_IP + IP);
    }
}
