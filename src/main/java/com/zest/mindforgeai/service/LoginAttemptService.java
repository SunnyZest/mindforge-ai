package com.zest.mindforgeai.service;

/**
 * 登录失败次数限制。
 * <p>
 * 防的是<b>暴力破解</b>：没有这个，攻击者写个脚本就能无限次猜密码。
 * <p>
 * 两个维度同时计数：
 * <ul>
 *   <li><b>按用户名</b> —— 防「盯着一个账号猛猜」</li>
 *   <li><b>按 IP</b> —— 防「同一来源换着账号撞库」</li>
 * </ul>
 * 阈值不一样：用户名严（默认 5 次），IP 松（默认 20 次）——
 * 因为公司/学校出口是 NAT，一堆人共用一个 IP，卡太死会误伤。
 *
 * @author crx
 * @since 2026-10-07
 */
public interface LoginAttemptService {

    /**
     * 登录前调用：如果账号或 IP 已被锁定，抛 {@code LOGIN_TOO_MANY_ATTEMPTS}。
     */
    void checkNotLocked(String username, String clientIp);

    /**
     * 认证失败时调用：累加计数，达到阈值就锁定。
     */
    void recordFailure(String username, String clientIp);

    /**
     * 登录成功时调用：清掉该账号的失败计数。
     * <p>
     * <b>刻意不清 IP 计数</b> —— 同一个 IP 上可能有别人正在撞库，
     * 攻击者自己登录成功一次就把 IP 计数清零，等于给了他无限次机会。
     */
    void recordSuccess(String username, String clientIp);

    /**
     * 修改密码前调用：如果账号或 IP 尝试修改密码失败过多，抛 {@code PASSWORD_CHANGE_TOO_MANY_ATTEMPTS}。
     */
    void checkPasswordChangeNotLocked(String username, String clientIp);

    /**
     * 修改密码验证旧密码失败时调用：累加计数，达到阈值就锁定。
     */
    void recordPasswordChangeFailure(String username, String clientIp);

    /**
     * 修改密码成功时调用：清空该用户的修改密码失败计数。
     */
    void recordPasswordChangeSuccess(String username, String clientIp);
}
