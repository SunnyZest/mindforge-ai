package com.zest.mindforgeai.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 流式对话线程池，对应 {@code mindforge.chat.stream.*}。
 *
 * <p>★ 这三个数必须是配置而不是常量：{@code maxPoolSize} 就是
 * <b>同时在聊的人数上限</b>，超出的请求会被 AbortPolicy 直接拒绝。
 * 不同机器（开发机 / 2C4G 服务器 / 8C16G）该给的值不一样，
 * 写死在代码里意味着每次调整都要重新发版。
 * <p>
 * ⚠️ 调大之前先确认下游扛得住：每个在跑的流 = 一个线程 + <b>一条到 Python 的长连接</b>，
 * 实际压力会转嫁给 Python 服务和 DashScope，不只是本进程的事。
 *
 * @author crx
 * @since 2026-10-18
 */
@Data
@Component
@ConfigurationProperties(prefix = "mindforge.chat.stream")
public class ChatStreamProperties {

    /** 常驻线程数。并发很低时也保持这么多，避免频繁创建 */
    private int corePoolSize = 4;

    /** 峰值上限。**这就是并发流式的天花板** —— 满了直接拒绝，不排队 */
    private int maxPoolSize = 16;

    /**
     * 队列容量。线程都忙时先排这么多个，满了才走拒绝策略。
     * <p>
     * ⚠️ 不要为了「少报错」把它调得很大：排队等 60 秒才轮到的回答，
     * 用户早就不看了，还白占着到 Python 的连接。
     */
    private int queueCapacity = 100;

    /** 优雅停机时最多等多少秒，等正在生成的回答写完（别把用户看了一半的流硬掐断） */
    private int awaitTerminationSeconds = 30;
}
