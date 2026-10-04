package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.JwtProperties;
import com.zest.mindforgeai.mapper.RefreshTokenMapper;
import com.zest.mindforgeai.pojo.entity.SysRefreshToken;
import com.zest.mindforgeai.service.RefreshTokenService;
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
import java.util.Base64;
import java.util.HexFormat;

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
    public String issue(Long userId) {
        String rawToken = generateRawToken();

        save(new SysRefreshToken()
                .setUserId(userId)
                .setTokenHash(sha256(rawToken))
                .setExpiresAt(LocalDateTime.now()
                        .plusDays(jwtProperties.getRefreshTokenExpireDays())));

        return rawToken;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RefreshResult refresh(String rawToken) {
        SysRefreshToken record = findByRawToken(rawToken);
        if (record == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "refreshToken 无效，请重新登录");
        }

        // 重放检测：已撤销的 token 又被使用，说明它被泄露了。
        // 正常流程下，合法用户早已换到新 token，旧的不会再出现。
        if (record.getRevokedAt() != null) {
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

        // 轮换：旧的立即作废，防止被偷后长期使用
        record.setRevokedAt(LocalDateTime.now());
        updateById(record);

        return new RefreshResult(record.getUserId(), issue(record.getUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String rawToken) {
        SysRefreshToken record = findByRawToken(rawToken);
        if (record == null || record.getRevokedAt() != null) {
            return;     // 幂等：不存在或已撤销，直接当成功
        }
        record.setRevokedAt(LocalDateTime.now());
        updateById(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAll(Long userId) {
        update(new LambdaUpdateWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getUserId, userId)
                .isNull(SysRefreshToken::getRevokedAt)
                .set(SysRefreshToken::getRevokedAt, LocalDateTime.now()));
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
