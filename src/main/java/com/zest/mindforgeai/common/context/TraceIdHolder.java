package com.zest.mindforgeai.common.context;

import org.slf4j.MDC;

/**
 * traceId 上下文：由 TraceIdFilter 写入，Result 读取后返回给前端
 *
 * @author crx
 * @since 2026-10-03
 */
public final class TraceIdHolder {

    public static final String MDC_KEY = "traceId";

    private TraceIdHolder() {
    }

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
