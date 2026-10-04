package com.zest.mindforgeai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类。
 * <p>
 * {@code @EnableScheduling} 用于启用定时任务（见 {@code task} 包），
 * 目前用于清理过期的 refresh token。
 */
@SpringBootApplication
@EnableScheduling
public class MindforgeAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindforgeAiApplication.class, args);
    }

}
