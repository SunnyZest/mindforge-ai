package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.pojo.vo.ConfirmTokenVO;
import com.zest.mindforgeai.security.LoginUser;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.SecondaryConfirmService;
import com.zest.mindforgeai.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 高危操作二次确认实现。
 *
 * @author crx
 * @since 2026-10-11
 */
@Slf4j
@Service
public class SecondaryConfirmServiceImpl implements SecondaryConfirmService {

    private static final String PREFIX_TOKEN = "mf:confirm:t:";
    private static final long EXPIRE_SECONDS = 300; // 5 分钟有效

    private final StringRedisTemplate redis;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public SecondaryConfirmServiceImpl(StringRedisTemplate redis,
                                       PasswordEncoder passwordEncoder,
                                       UserMapper userMapper,
                                       AuditService auditService) {
        this.redis = redis;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    @Override
    public ConfirmTokenVO issueConfirmToken(String password) {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Long userId = loginUser.getUserId();

        User user = userMapper.selectById(userId);
        if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            auditService.recordFailure("SECONDARY_CONFIRM", "AUTH", String.valueOf(userId),
                    Map.of("reason", "密码校验失败"));
            throw new BizException(ErrorCode.CONFIRM_FAILED, "二次确认密码不正确");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            redis.opsForValue().set(PREFIX_TOKEN + token, String.valueOf(userId), Duration.ofSeconds(EXPIRE_SECONDS));
        } catch (Exception e) {
            log.error("写入二次确认 Token 失败 (Redis 不可用？) userId={}", userId, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "安全凭据服务暂时不可用，请稍后再试");
        }

        auditService.recordSuccess("SECONDARY_CONFIRM", "AUTH", String.valueOf(userId),
                Map.of("action", "ISSUE_CONFIRM_TOKEN", "expiresIn", EXPIRE_SECONDS));

        log.info("用户成功获取二次确认凭据 userId={}", userId);
        return new ConfirmTokenVO(token, EXPIRE_SECONDS);
    }

    @Override
    public void validateAndConsume(HttpServletRequest request) {
        if (request == null) {
            return;
        }

        LoginUser loginUser = SecurityUtils.getLoginUser();
        Long userId = loginUser.getUserId();

        String confirmToken = request.getHeader(HEADER_CONFIRM_TOKEN);
        String confirmPassword = request.getHeader(HEADER_CONFIRM_PASSWORD);

        // 1. 优先校验 X-Confirm-Token（单次消费模式）
        if (StringUtils.hasText(confirmToken)) {
            String key = PREFIX_TOKEN + confirmToken.trim();
            String storedUserId;
            try {
                storedUserId = redis.opsForValue().get(key);
            } catch (Exception e) {
                log.error("读取二次确认 Token 失败 userId={}", userId, e);
                throw new BizException(ErrorCode.SYSTEM_ERROR, "二次确认服务不可用");
            }

            if (!StringUtils.hasText(storedUserId) || !storedUserId.equals(String.valueOf(userId))) {
                throw new BizException(ErrorCode.CONFIRM_FAILED, "二次确认凭证无效或已过期");
            }

            // 单次使用，立即核销
            try {
                redis.delete(key);
            } catch (Exception e) {
                log.warn("核销二次确认 Token 失败 token={}", confirmToken, e);
            }
            log.info("二次确认 Token 校验通过并已核销 userId={}", userId);
            return;
        }

        // 2. 备选方案：直接在 Header 中携带 X-Confirm-Password
        if (StringUtils.hasText(confirmPassword)) {
            User user = userMapper.selectById(userId);
            if (user == null || user.getDeleted() != null && user.getDeleted() == 1) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            if (!passwordEncoder.matches(confirmPassword.trim(), user.getPasswordHash())) {
                auditService.recordFailure("SECONDARY_CONFIRM_HEADER", "AUTH", String.valueOf(userId),
                        Map.of("reason", "X-Confirm-Password 错误"));
                throw new BizException(ErrorCode.CONFIRM_FAILED, "二次确认密码不正确");
            }
            log.info("二次确认密码校验通过 userId={}", userId);
            return;
        }

        // 3. 两个头均未提供，触发拦截
        log.warn("高危操作缺少二次确认凭据 userId={} uri={}", userId, request.getRequestURI());
        throw new BizException(ErrorCode.CONFIRM_REQUIRED,
                "该操作属于高危操作，请在请求头提供 X-Confirm-Token 或 X-Confirm-Password 进行二次确认");
    }
}
