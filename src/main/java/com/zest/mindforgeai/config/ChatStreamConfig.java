package com.zest.mindforgeai.config;

import com.zest.mindforgeai.common.properties.ChatStreamProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 流式对话用的线程池。
 * <p>
 * <b>为什么必须单独一个池、不能用默认的：</b>
 * SSE 推送期间，一个线程会<b>从头占到尾</b> —— 它要一直阻塞在读 Python 的流上
 * （一段回答 10~60 秒）。而 Spring Boot 默认的 {@code applicationTaskExecutor}
 * 是给 {@code @Async} 和框架内部用的，被几个聊天流占满之后，
 * 其它异步任务（通知、清理任务）就全排不上队了。
 * <p>
 * <b>为什么池子是有界的：</b>每个在跑的流 = 一个线程 + 一条到 Python 的长连接。
 * 无界队列在并发上来时会一路堆积到 OOM，而且用户那边其实早就超时了。
 * 满了就<b>快速拒绝</b>（{@link ThreadPoolExecutor.AbortPolicy}），
 * 让调用方立刻拿到错误，比排队等到天荒地老强。
 * <p>
 * ⚠️ 池大小按「预期同时在聊的人数」调，改 {@code application.yml} 里的
 * {@code mindforge.chat.stream.*} 即可 —— {@code maxPoolSize} 就是并发天花板，
 * 这个是压测唯一该看的数字（2026-10-18 从常量改成配置，见
 * {@link ChatStreamProperties}）。
 * <p>
 * ⚠️ <b>副作用（知道就好，暂时无影响）</b>：定义了这个 {@code Executor} 之后，
 * Spring Boot 的 {@code applicationTaskExecutor} 会退让（它的条件是
 * {@code @ConditionalOnMissingBean(Executor.class)}）。所以将来如果有人用
 * {@code @Async}，要自己再补一个 {@code applicationTaskExecutor}，
 * 别指望默认那个还在。目前项目里没有任何 {@code @Async} / {@code Callable} 返回，
 * {@code @Scheduled} 用的是另一个 bean（{@code taskScheduler}），都不受影响。
 *
 * @author crx
 * @since 2026-10-10
 */
@Configuration
public class ChatStreamConfig {

    private final ChatStreamProperties properties;

    public ChatStreamConfig(ChatStreamProperties properties) {
        this.properties = properties;
    }

    @Bean(name = "chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("chat-stream-");
        // 满了直接抛，不阻塞调用方 —— 见类注释
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 优雅停机：等正在生成的回答写完，别把用户看了一半的流硬掐断
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }
}
