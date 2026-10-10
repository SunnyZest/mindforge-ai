package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.context.TraceIdHolder;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.mapper.AuditLogMapper;
import com.zest.mindforgeai.pojo.entity.SysAuditLog;
import com.zest.mindforgeai.pojo.vo.AuditLogVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。 */
    private static final Map<String, SFunction<SysAuditLog, ?>> SORTABLE = Map.of(
            "action", SysAuditLog::getAction,
            "resourceType", SysAuditLog::getResourceType,
            "result", SysAuditLog::getResult,
            "createdAt", SysAuditLog::getCreatedAt);

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
        // 操作人从 SecurityContext 取（普通业务接口走这条）
        doRecord(currentUserId(), action, resourceType, resourceId, success, detail);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(Long userId, String action, String resourceType, String resourceId,
                       boolean success, Object detail) {
        // 操作人由调用方显式指定（定时任务、异步线程等无 SecurityContext 的场景）
        doRecord(userId, action, resourceType, resourceId, success, detail);
    }

    /**
     * 真正的写入逻辑。
     * <p>
     * userId 由调用方决定来源（显式传入 或 从 SecurityContext 取），这里只负责组装与落库。
     */
    private void doRecord(Long userId, String action, String resourceType, String resourceId,
                          boolean success, Object detail) {
        try {
            SysAuditLog audit = new SysAuditLog()
                    .setAction(action)
                    .setResourceType(resourceType)
                    .setResourceId(resourceId)
                    .setResult(success ? AuditConstants.RESULT_SUCCESS : AuditConstants.RESULT_FAIL)
                    .setRequestId(TraceIdHolder.get())
                    .setDetailJson(toJson(detail))
                    .setUserId(userId);

            fillRequestInfo(audit);

            save(audit);
        } catch (Exception e) {
            // 审计是旁路能力，绝不能因为写审计失败而中断主流程
            log.error("写审计日志失败 action={} resourceType={} resourceId={}",
                    action, resourceType, resourceId, e);
        }
    }

    /**
     * ⚠️ 这里必须自己标 {@code REQUIRES_NEW}，不能靠内部调 {@code record()} 继承 ——
     * <b>同类内部调用不走 Spring 代理</b>，方法上的事务注解不会生效。
     * <p>
     * 不加会怎样：调用方是 {@code @Transactional} 方法时（比如 assignRoles），
     * 抛异常回滚会把这条审计一起回滚掉 —— 结果就是
     * <b>「失败的操作」永远审计不到</b>，而失败恰恰是最该留痕的。
     * 实测踩到过：垂直越权被拦截，审计表里一条记录都没有。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordSuccess(String action, String resourceType, String resourceId, Object detail) {
        doRecord(currentUserId(), action, resourceType, resourceId, true, detail);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(String action, String resourceType, String resourceId, Object detail) {
        doRecord(currentUserId(), action, resourceType, resourceId, false, detail);
    }

    @Override
    public PageResultVO<AuditLogVO> pageLogs(long page, long pageSize,
                                             Long userId, String action, String resourceType,
                                             String result, LocalDateTime from, LocalDateTime to,
                                             String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        LambdaQueryWrapper<SysAuditLog> wrapper = new LambdaQueryWrapper<SysAuditLog>()
                .eq(userId != null, SysAuditLog::getUserId, userId)
                .eq(StringUtils.hasText(action), SysAuditLog::getAction, action)
                .eq(StringUtils.hasText(resourceType), SysAuditLog::getResourceType, resourceType)
                .eq(StringUtils.hasText(result), SysAuditLog::getResult, result)
                .ge(from != null, SysAuditLog::getCreatedAt, from)
                .le(to != null, SysAuditLog::getCreatedAt, to);

        // 兜底按 id 倒序而不是 createdAt：同一秒内多条时 id 才能保证稳定顺序
        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, SysAuditLog::getId, false);

        Page<SysAuditLog> pageResult = page(new Page<>(safePage, safeSize), wrapper);

        List<AuditLogVO> records = pageResult.getRecords().stream()
                .map(AuditLogVO::of)
                .toList();

        return PageResultVO.of(pageResult, records);
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
