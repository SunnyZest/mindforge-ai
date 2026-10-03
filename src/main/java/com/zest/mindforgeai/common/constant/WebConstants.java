package com.zest.mindforgeai.common.constant;

/**
 * Web 层通用常量
 *
 * @author crx
 * @since 2026-10-03
 */
public final class WebConstants {

    private WebConstants() {
    }

    /** 认证请求头，文档 §8.3 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** 认证请求头前缀，注意结尾带空格 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 链路追踪请求头，文档 §8.3 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** JSON 响应类型 */
    public static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";
}
