package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置，对应 application.yml 的 mindforge.storage.*
 * <p>
 * 通过 {@code type} 切换存储后端，业务代码只依赖 {@code FileStorageService} 接口，
 * 换存储不需要改业务逻辑。
 *
 * @author crx
 * @since 2026-10-05
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.storage")
public class StorageProperties {

    /** 存储类型：OSS / MINIO / LOCAL */
    private String type = "OSS";

    /**
     * 已删除文件的<b>恢复窗口</b>（天）。
     * <p>
     * 用户删除文件时只做逻辑删除，OSS 对象<b>不立即删</b> ——
     * 超过这个天数后，后台清理任务才真正物理删除。
     * 这样误删还有救，存储最终也会释放。
     */
    private int retentionDays = 30;

    /** 清理任务的 cron，默认每天 03:20 */
    private String purgeCron = "0 20 3 * * ?";

    private Oss oss = new Oss();

    @Data
    public static class Oss {

        /**
         * 访问域名，如 {@code oss-cn-hangzhou.aliyuncs.com}。
         * <p>
         * ⚠️ 必须用<b>公网</b> endpoint，不要用 {@code -internal} 结尾的内网地址 ——
         * 内网地址只有同地域 ECS 能访问，本机连不上，预签名 URL 也会失效。
         */
        private String endpoint;

        /** 桶名 */
        private String bucket;

        /** RAM 子账号的 AccessKey ID */
        private String accessKeyId;

        /** RAM 子账号的 AccessKey Secret */
        private String accessKeySecret;
    }
}
