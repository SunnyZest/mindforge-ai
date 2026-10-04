package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.SysRefreshToken;

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
 * </ul>
 *
 * @author crx
 * @since 2026-10-04
 */
public interface RefreshTokenService extends IService<SysRefreshToken> {

    /**
     * 签发一个新的 refresh token。
     *
     * @return 明文 token（只在此刻返回，库里只存哈希）
     */
    String issue(Long userId);

    /**
     * 用 refresh token 换一对新令牌（含轮换与重放检测）。
     *
     * @param rawToken 前端传来的明文 refresh token
     * @return 新的 refresh token 明文 + 归属用户
     */
    RefreshResult refresh(String rawToken);

    /** 登出：撤销指定 refresh token（幂等，重复调用不报错） */
    void revoke(String rawToken);

    /** 撤销某用户全部 refresh token（登出所有设备 / 检测到重放时使用） */
    void revokeAll(Long userId);

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
     */
    record RefreshResult(Long userId, String refreshToken) {
    }
}
