package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.SessionConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.JwtProperties;
import com.zest.mindforgeai.mapper.RefreshTokenMapper;
import com.zest.mindforgeai.pojo.entity.SysRefreshToken;
import com.zest.mindforgeai.pojo.vo.SessionVO;
import com.zest.mindforgeai.service.RefreshTokenService;
import com.zest.mindforgeai.utils.UserAgentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 刷新令牌服务实现
 *
 * @author crx
 * @since 2026-10-04
 */
@Slf4j
@Service
public class RefreshTokenServiceImpl extends ServiceImpl<RefreshTokenMapper, SysRefreshToken>
        implements RefreshTokenService {

    /** token 随机字节数：32 字节 = 256 bit，Base64URL 后约 43 字符 */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtProperties jwtProperties;

    /**
     * 用于「独立事务」执行：重放检测触发的 revokeAll 必须在抛出异常前提交，
     * 否则会被 refresh() 自身事务的回滚一起撤销。
     * <p>
     * 为什么不用 {@code @Transactional(REQUIRES_NEW)}：{@link #refresh} 里是
     * {@code this.revokeAll(...)} 自调用，注解会被 Spring AOP 代理忽略，不起作用。
     */
    private final TransactionTemplate requiresNewTx;

    public RefreshTokenServiceImpl(JwtProperties jwtProperties,
                                   PlatformTransactionManager transactionManager) {
        this.jwtProperties = jwtProperties;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String issue(Long userId, Long sessionId, String endType, String ip, String userAgent) {
        String rawToken = generateRawToken();
        LocalDateTime now = LocalDateTime.now();

        // sessionId 为空说明是「第一次签发」（登录），给它开一个新会话。
        // 刷新轮转时调用方会把老会话传进来，这里就不会走到。
        Long sid = sessionId != null ? sessionId : IdWorker.getId();

        save(new SysRefreshToken()
                .setUserId(userId)
                .setTokenHash(sha256(rawToken))
                .setExpiresAt(now.plusDays(jwtProperties.getRefreshTokenExpireDays()))
                .setSessionId(sid)
                .setEndType(endType)
                .setIp(ip)
                // ⚠️ 必须截断：UA 是客户端随便写的，超长会让 varchar(512) 抛 Data too long
                .setUserAgent(UserAgentParser.truncate(userAgent))
                .setLastActiveAt(now));

        return rawToken;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RefreshResult refresh(String rawToken) {
        SysRefreshToken record = findByRawToken(rawToken);
        if (record == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "refreshToken 无效，请重新登录");
        }

        // 已撤销的 token 又被使用 —— 但「为什么撤销」决定了要不要按泄露处理：
        //
        //   ROTATED（轮转作废）：这个 token 已经换过一次新的，现在又冒出来，
        //       说明它被复制走了 → 判定泄露，撤销该用户全部会话。
        //   LOGOUT / KICKED / REVOKE_OTHERS：用户主动作废的，客户端并不知道，
        //       下次刷新仍会拿着旧 token 来。这是【正常现象】，只拒绝这一次。
        //       ⚠️ 不区分的话，「在 A 设备踢掉 B」会让 B 一刷新就把 A 也带下线。
        //   null（加字段之前的老数据）：保持旧行为，按泄露处理。
        if (record.getRevokedAt() != null) {
            String reason = record.getRevokedReason();
            if (reason != null && !SessionConstants.RevokeReason.ROTATED.equals(reason)) {
                log.info("使用已作废的 refreshToken（原因 {}），拒绝本次刷新 userId={}",
                        reason, record.getUserId());
                throw new BizException(ErrorCode.TOKEN_INVALID, "该会话已退出，请重新登录");
            }

            Long userId = record.getUserId();
            log.warn("检测到 refreshToken 重放！userId={} tokenId={}，撤销该用户全部 refreshToken",
                    userId, record.getId());

            // ⚠️ 必须用独立事务提交：本方法带 @Transactional，下面抛出的 BizException
            //    会回滚当前事务；若 revokeAll 参与其中，等于白做（重放检测失效）。
            requiresNewTx.executeWithoutResult(status -> revokeAll(userId));

            throw new BizException(ErrorCode.TOKEN_INVALID, "refreshToken 已失效，请重新登录");
        }

        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED, "refreshToken 已过期，请重新登录");
        }

        // 轮换：旧的立即作废，防止被偷后长期使用。
        // 原因记成 ROTATED —— 只有它会在下次被使用时触发重放检测。
        record.setRevokedAt(LocalDateTime.now())
                .setRevokedReason(SessionConstants.RevokeReason.ROTATED);
        updateById(record);

        // ★ 会话继承：新 token 沿用同一个 sessionId，否则每刷新一次
        //   「登录设备列表」就多一行（access token 30 分钟一刷，一天 48 行）。
        //   老 token（加字段之前签发的）没有 sessionId，这里补一个新的 ——
        //   用户刷新一次就自动补上，不需要专门写迁移脚本。
        Long sessionId = record.getSessionId() != null ? record.getSessionId() : IdWorker.getId();

        // IP / UA 沿用登录时的：刷新请求不重新采集。UA 可能变（浏览器升级），
        // 但跟着变会让用户以为「换设备了」，反而误导。
        String newToken = issue(record.getUserId(), sessionId, record.getEndType(),
                record.getIp(), record.getUserAgent());

        return new RefreshResult(record.getUserId(), newToken, sessionId, record.getEndType());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String rawToken) {
        SysRefreshToken record = findByRawToken(rawToken);
        if (record == null || record.getRevokedAt() != null) {
            return;     // 幂等：不存在或已撤销，直接当成功
        }
        record.setRevokedAt(LocalDateTime.now())
                .setRevokedReason(SessionConstants.RevokeReason.LOGOUT);
        updateById(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAll(Long userId) {
        update(new LambdaUpdateWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getUserId, userId)
                .isNull(SysRefreshToken::getRevokedAt)
                .set(SysRefreshToken::getRevokedAt, LocalDateTime.now())
                .set(SysRefreshToken::getRevokedReason, SessionConstants.RevokeReason.REVOKE_ALL));
    }

    @Override
    public List<SessionVO> listSessions(Long userId, String endType, Long currentSessionId) {
        List<SysRefreshToken> rows = list(new LambdaQueryWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getUserId, userId)
                .eq(endType != null, SysRefreshToken::getEndType, endType)
                .isNull(SysRefreshToken::getRevokedAt)
                .gt(SysRefreshToken::getExpiresAt, LocalDateTime.now())
                // 老数据没有 sessionId，既列不出来也踢不掉 —— 直接过滤，
                // 避免出现一条 sessionId 为空的幽灵会话
                .isNotNull(SysRefreshToken::getSessionId)
                // 按 id 倒序：雪花 id 时间有序，比 created_at（秒级精度）
                // 更适合做「同一会话取最新一条」的依据
                .orderByDesc(SysRefreshToken::getId));

        // 按 sessionId 聚合，每个会话只保留第一条（即最新那条）
        Map<Long, SysRefreshToken> latestPerSession = new LinkedHashMap<>();
        for (SysRefreshToken row : rows) {
            latestPerSession.putIfAbsent(row.getSessionId(), row);
        }

        List<SessionVO> result = new ArrayList<>(latestPerSession.size());
        for (SysRefreshToken row : latestPerSession.values()) {
            result.add(toVO(row, currentSessionId));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeSession(Long userId, Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        // ⚠️ userId 条件是防水平越权的关键：少了它，任何人拿到别人的
        //    sessionId 就能把对方踢下线
        int affected = baseMapper.update(null, new LambdaUpdateWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getUserId, userId)
                .eq(SysRefreshToken::getSessionId, sessionId)
                .isNull(SysRefreshToken::getRevokedAt)
                .set(SysRefreshToken::getRevokedAt, LocalDateTime.now())
                .set(SysRefreshToken::getRevokedReason, SessionConstants.RevokeReason.KICKED));
        return affected > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revokeOthers(Long userId, String endType, Long keepSessionId) {
        LambdaUpdateWrapper<SysRefreshToken> wrapper = new LambdaUpdateWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getUserId, userId)
                .eq(endType != null, SysRefreshToken::getEndType, endType)
                .isNull(SysRefreshToken::getRevokedAt)
                .set(SysRefreshToken::getRevokedAt, LocalDateTime.now())
                .set(SysRefreshToken::getRevokedReason,
                        SessionConstants.RevokeReason.REVOKE_OTHERS);

        if (keepSessionId != null) {
            // ⚠️ 不能无条件写 .ne(sessionId, keep)：SQL 里 session_id <> NULL 恒为
            //    UNKNOWN，一条都更新不到。为空时走另一分支（等于踢该端全部）。
            wrapper.ne(SysRefreshToken::getSessionId, keepSessionId);
        }
        return baseMapper.update(null, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeExpired(int limit) {
        // 只删「已过期」的：已撤销但未过期的要留着做重放检测
        return baseMapper.delete(new LambdaQueryWrapper<SysRefreshToken>()
                .lt(SysRefreshToken::getExpiresAt, LocalDateTime.now())
                .last("LIMIT " + Math.max(1, limit)));
    }

    /* ---------- 内部工具 ---------- */

    private SessionVO toVO(SysRefreshToken row, Long currentSessionId) {
        SessionVO vo = new SessionVO();
        vo.setSessionId(String.valueOf(row.getSessionId()));
        vo.setDeviceName(UserAgentParser.describe(row.getUserAgent()));
        vo.setIp(row.getIp());
        vo.setEndType(row.getEndType());
        vo.setCreatedAt(row.getCreatedAt());
        // 老数据没有 last_active_at，退回签发时间 —— 别给前端一个 null 让它显示成「-」
        vo.setLastActiveAt(row.getLastActiveAt() != null ? row.getLastActiveAt() : row.getCreatedAt());
        vo.setCurrent(currentSessionId != null && currentSessionId.equals(row.getSessionId()));
        return vo;
    }

    private SysRefreshToken findByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getTokenHash, sha256(rawToken)));
    }

    /** 32 字节安全随机数 → Base64URL（无填充），约 43 字符 */
    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 十六进制摘要。
     * <p>
     * 用 SHA-256 而不是 BCrypt：refresh token 是高熵随机串（不是弱密码），
     * 不存在字典/彩虹表破解的风险，且每次刷新都要查库，需要快。
     */
    private String sha256(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
