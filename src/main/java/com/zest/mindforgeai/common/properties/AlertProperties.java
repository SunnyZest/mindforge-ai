package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 高危操作告警配置，对应 application.yml 的 mindforge.alert.*。
 *
 * @author crx
 * @since 2026-10-11
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.alert")
public class AlertProperties {

    /**
     * 告警外推的 webhook 地址（企业微信 / 钉钉自定义机器人的 text 格式）。
     * <p>
     * 留空 = 只入库、不外推 —— 告警的核心是「管理端有个地方能看」，
     * 推送是锦上添花，没配也不影响功能。生产建议配上，否则又回到
     * 「没人主动看等于没有」的老问题。
     */
    private String webhookUrl;

    /** 单次推送超时（秒）。webhook 是尽力而为的旁路，超时要短，不能拖慢主请求。 */
    private int webhookTimeoutSeconds = 3;
}
