package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.context.TraceIdHolder;
import com.zest.mindforgeai.mapper.AuditLogMapper;
import com.zest.mindforgeai.pojo.entity.SysAuditLog;
import com.zest.mindforgeai.security.LoginUser;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计日志服务实现
 *
 * @author crx
 * @since 2026-10-03
 */
@Slf4j
@Service
public class AuditServiceImpl extends ServiceImpl<AuditLogMapper, SysAuditLog>
        implements AuditService {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String UNKNOWN = "unknown";

    private final ObjectMapper objectMapper;

    public AuditServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 用独立事务写入（REQUIRES_NEW）：
     * 业务回滚不会带走审计记录，审计写入失败也不会污染业务事务。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(String action, String resourceType, String resourceId,
                       boolean success, Object detail) {
        try {
            SysAuditLog audit = new SysAuditLog()
                    .setAction(action)
                    .setResourceType(resourceType)
                    .setResourceId(resourceId)
                    .setResult(success ? AuditConstants.RESULT_SUCCESS : AuditConstants.RESULT_FAIL)
                    .setRequestId(TraceIdHolder.get())
                    .setDetailJson(toJson(detail))
                    .setUserId(currentUserId());

            fillRequestInfo(audit);

            save(audit);
        } catch (Exception e) {
            // 审计是旁路能力，绝不能因为写审计失败而中断主流程
            log.error("写审计日志失败 action={} resourceType={} resourceId={}",
                    action, resourceType, resourceId, e);
        }
    }

    @Override
    public void recordSuccess(String action, String resourceType, String resourceId, Object detail) {
        record(action, resourceType, resourceId, true, detail);
    }

    @Override
    public void recordFailure(String action, String resourceType, String resourceId, Object detail) {
        record(action, resourceType, resourceId, false, detail);
    }

    /** 当前登录用户 id；未登录（如登录失败）返回 null */
    private Long currentUserId() {
        LoginUser loginUser = SecurityUtils.getLoginUserOrNull();
        return loginUser == null ? null : loginUser.getUserId();
    }

    /** 补全 HTTP 上下文；非 HTTP 线程（定时任务等）时跳过
        从HttpServletRequest中取出对应的Method、url、ipAddr*/
    private void fillRequestInfo(SysAuditLog audit) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        audit.setHttpMethod(truncate(request.getMethod(), 16))
                .setRequestUri(truncate(request.getRequestURI(), 500))
                .setIpAddress(truncate(resolveClientIp(request), 64))
                .setUserAgent(truncate(request.getHeader(HEADER_USER_AGENT), 500));
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    /** 优先取代理头（Nginx 已配置 X-Real-IP / X-Forwarded-For），最后才用 remoteAddr */
    private String resolveClientIp(HttpServletRequest request) {
        String ip = firstIp(request.getHeader(HEADER_FORWARDED_FOR));
        if (ip == null) {
            ip = firstIp(request.getHeader(HEADER_REAL_IP));
        }
        if (ip == null) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /** X-Forwarded-For 形如「client, proxy1, proxy2」，取第一个；空值或 unknown 返回 null */
    private String firstIp(String header) {
        if (!StringUtils.hasText(header) || UNKNOWN.equalsIgnoreCase(header)) {
            return null;
        }
        String first = header.split(",")[0].trim();
        return StringUtils.hasText(first) && !UNKNOWN.equalsIgnoreCase(first) ? first : null;
    }

    /** 对value进行切分，取maxLength长度的值*/
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 序列化 detail；失败时降级为字符串，绝不因为序列化问题丢审计 */
    private String toJson(Object detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("审计 detail 序列化失败，降级为字符串", e);
            return "\"" + String.valueOf(detail).replace("\"", "\\\"") + "\"";
        }
    }
}
