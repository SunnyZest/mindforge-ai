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
     * 读超时 —— <b>要按最慢的接口来定</b>。
     * <p>
     * 对话接口要等大模型逐 token 生成完整回答，几十秒是正常的，
     * 给 3 秒会大面积超时。检索接口快得多，但没必要分开配。
     */
    private Duration readTimeout = Duration.ofSeconds(120);
}
