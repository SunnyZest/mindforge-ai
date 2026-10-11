package com.zest.mindforgeai.service;

import com.zest.mindforgeai.common.properties.AlertProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 告警 webhook 外推。
 * <p>
 * <b>定位</b>：纯旁路的「尽力而为」—— 任何失败只打 WARNING，
 * 一次高危操作不能因为「推送失败」产生任何业务影响（与 AI 使用量上报同一套约定）。
 * <p>
 * <b>报文格式</b>：{@code {"msgtype":"text","text":{"content":...}}} ——
 * 企业微信与钉钉的自定义机器人都直接认这个格式；飞书的字段不同，
 * 真要用时在网关侧转换，或再加一种 sender。
 * <p>
 * <b>为什么用虚拟线程</b>：推送是 fire-and-forget，主请求不该等它；
 * Java 21 的虚拟线程让「起了就不管」不需要维护线程池的生命周期。
 * ⚠️ 代价是进程退出时在飞的推送可能发不出去 —— 丢一两条通知可接受，
 * 告警本体已经在库里了。
 *
 * @author crx
 * @since 2026-10-11
 */
@Slf4j
@Service
public class AlertNotifier {

    /** webhook 正文上限：企业微信 text 内容上限 2048 字节，留出 JSON 结构的余量 */
    private static final int MAX_CONTENT_CHARS = 1800;

    private final AlertProperties properties;
    private final RestClient restClient;

    public AlertNotifier(AlertProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout(properties.getWebhookTimeoutSeconds() * 1000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 未配置 webhook 时直接跳过（只入库不外推是合法形态）。 */
    public void notify(String content) {
        String url = properties.getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }

        String body = buildContent(content);
        Thread.ofVirtual().name("alert-webhook-").start(() -> {
            try {
                restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"msgtype\":\"text\",\"text\":{\"content\":"
                                + quote(body) + "}}")
                        .retrieve()
                        .toBodilessEntity();
                log.debug("告警 webhook 推送完成");
            } catch (Exception e) {
                // ⚠️ 只打 WARNING。告警本体已入库，推送失败不影响任何业务。
                log.warn("告警 webhook 推送失败（不影响业务）：{}", e.toString());
            }
        });
    }

    private String buildContent(String content) {
        String text = content == null ? "" : content;
        return text.length() <= MAX_CONTENT_CHARS ? text : text.substring(0, MAX_CONTENT_CHARS);
    }

    /** 最小 JSON 字符串转义：反斜杠和双引号。内容来自服务端自己拼的告警摘要，不含其他控制字符场景。 */
    private String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
