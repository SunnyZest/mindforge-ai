package com.zest.mindforgeai.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.zest.mindforgeai.common.properties.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储配置。
 * <p>
 * 只有在 {@code mindforge.storage.type=OSS} 时才创建 OSS 客户端 ——
 * 将来切到 MinIO/本地磁盘时，这里不会因为缺配置而启动失败。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Configuration
public class StorageConfig {

    /**
     * OSS 客户端。<b>单例</b>即可 —— OSS SDK 内部维护连接池且线程安全，
     * 每次请求 new 一个会耗尽连接。
     * <p>
     * {@code destroyMethod = "shutdown"} 保证应用关闭时释放连接池。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "mindforge.storage.type", havingValue = "OSS")
    public OSS ossClient(StorageProperties props) {
        StorageProperties.Oss oss = props.getOss();
        log.info("初始化 OSS 客户端：endpoint={} bucket={}", oss.getEndpoint(), oss.getBucket());
        return new OSSClientBuilder().build(
                oss.getEndpoint(), oss.getAccessKeyId(), oss.getAccessKeySecret());
    }
}
