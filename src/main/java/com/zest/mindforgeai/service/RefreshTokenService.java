package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.SysRefreshToken;
import com.zest.mindforgeai.pojo.vo.SessionVO;

import java.util.List;

/**
 * 刷新令牌服务。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>refresh token 是<b>不透明随机串</b>（不是 JWT）—— 它必须可撤销，查库不可避免，
 *       用 JWT 的"无状态"优势没有意义；不透明串还更短、不含任何信息</li>
 *   <li>库里只存 <b>SHA-256 哈希</b>，明文只在签发那一刻返回给前端</li>
 *   <li>每次刷新都<b>轮换</b>：签发新的、立即作废旧的那个</li>
 *   <li>已撤销的 token 被再次使用 → 判定为泄露，撤销该用户<b>全部</b> token</li>
 *   <li><b>会话</b>：一次登录生成一个 sessionId，之后每次轮转都继承它。
 *       没有这一层，一个设备登一天会产生 48 条记录（access token 30 分钟一刷），
 *       「登录设备列表」就变成一堆重复项</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-04
 */
public interface RefreshTokenService extends IService<SysRefreshToken> {

    /**
     * 签发一个新的 refresh token，并把它归到指定会话下。
     *
     * @param userId    归属用户
     * @param sessionId 会话 id；为 null 表示独立的一次登录（内部会补一个）
     * @param endType   登录端，见 {@code SessionConstants.END_APP} / {@code END_ADMIN}
     * @param ip        登录 IP
     * @param userAgent UA 原文（内部会截断到字段长度）
     * @return 明文 token（只在此刻返回，库里只存哈希）
     */
    String issue(Long userId, Long sessionId, String endType, String ip, String userAgent);

    /**
     * 用 refresh token 换一对新令牌（含轮换与重放检测）。
     *
     * @param rawToken 前端传来的明文 refresh token
     * @return 新的 refresh token 明文 + 归属用户 + 会话信息
     */
    RefreshResult refresh(String rawToken);

    /** 登出：撤销指定 refresh token（幂等，重复调用不报错） */
    void revoke(String rawToken);

    /** 撤销某用户全部 refresh token（登出所有设备 / 检测到重放时使用） */
    void revokeAll(Long userId);

    /**
     * 列出某用户某个端当前存活的会话（登录设备列表）。
     * <p>
     * 按 sessionId 聚合，每个会话只返回最新那条 token 的信息。
     *
     * @param userId           归属用户
     * @param endType          端；null 表示不限
     * @param currentSessionId 当前请求所在的会话，用于把这一条标成「本机」；可为 null
     */
    List<SessionVO> listSessions(Long userId, String endType, Long currentSessionId);

    /**
     * 踢掉一个指定会话。
     * <p>
     * ⚠️ 必须带 userId 条件：只按 sessionId 更新的话，任何人拿到别人的
     * sessionId 就能踢掉对方 —— 典型的水平越权。
     *
     * @return 是否真的踢掉了（false = 会话不存在 / 已失效 / 不是这个人的）
     */
    boolean revokeSession(Long userId, Long sessionId);

    /**
     * 踢掉「除当前会话外的全部会话」，只作用于同一端。
     * <p>
     * 为什么不跨端：用户端和管理端是两个权限空间，在用户端点「退出其他设备」
     * 把管理端也踢了，用户会莫名其妙。要全踢就走登出。
     *
     * @param keepSessionId 要保留的会话；为 null 时等于踢掉该端全部
     * @return 实际踢掉的会话数
     */
    int revokeOthers(Long userId, String endType, Long keepSessionId);

    /**
     * 删除<b>已过期</b>的 refresh token（供定时任务分批调用）。
     * <p>
     * 注意条件只按「过期」：已撤销但未过期的必须保留，那是重放检测的依据。
     *
     * @param limit 单批最多删除条数
     * @return 实际删除条数
     */
    int removeExpired(int limit);

    /**
     * 刷新结果。
     *
     * @param userId       归属用户
     * @param refreshToken 新的 refresh token 明文
     * @param sessionId    会话 id（签发新 access token 时要带进 sid claim）
     * @param endType      登录端
     */
    record RefreshResult(Long userId, String refreshToken, Long sessionId, String endType) {
    }
}
