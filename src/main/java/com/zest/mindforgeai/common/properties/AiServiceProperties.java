package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 服务（Python）的连接配置，对应 {@code mindforge.ai-service.*}。
 * <p>
 * 方向说明：<b>这里是 Java 调 Python</b>（发起检索、对话）。
 * 反方向（Python 调 Java 拉文档、回写状态）走的是 {@code /internal/**}，
 * 鉴权用 {@code mindforge.internal.service-token}，是另一套配置。
 * <p>
 * ⚠️ 两边的 token 是<b>同一个值</b> —— 都放 {@code X-Service-Token} 头，
 * 互相校验。
 *
 * @author crx
 * @since 2026-10-08
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.ai-service")
public class AiServiceProperties {

    /** Python 服务地址，如 http://127.0.0.1:8000 */
    private String baseUrl;

    /** 服务间令牌，和 Python 的 JAVA_SERVICE_TOKEN 配成一样 */
    private String serviceToken;

    /** 建立连接的超时。AI 服务在本地，正常毫秒级 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * 读超时 —— <b>要按最慢的接口来定，而且必须大于 Python 侧的最坏耗时</b>。
     * <p>
     * Python 侧最坏耗时不是「单次超时」，而是
     * {@code extract_timeout_seconds × (1 + llm_max_retries)} ——
     * OpenAI SDK 会自动重试，一次超时不会直接失败，而是再试一遍。
     * 现在是 300 × 2 = 600 秒，所以这里给 660 秒（留余量）。
     * <p>
     * ⚠️ 配小了的表现很有迷惑性：Java 先掐断连接，响应只剩半截、连 Content-Type
     * 都读不到，客户端拿到的是「content type [application/octet-stream] 转换失败」
     * 或者一个笼统的「AI 服务超时」—— 而 Python 那边其实还在跑、最后是成功的。
     * 详细的两次踩坑记录见 {@code application.yml} 里这个字段的注释。
     */
    private Duration readTimeout = Duration.ofSeconds(660);
}
