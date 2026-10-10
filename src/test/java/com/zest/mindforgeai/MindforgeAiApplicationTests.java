package com.zest.mindforgeai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文能否启动的冒烟测试。
 *
 * <p>⚠️ 必须带上 {@code test} profile —— 否则会用默认的 dev profile，
 * 也就是连【开发库】。跑个测试连开发库是不该有的副作用，
 * 而且换台机器没配好 dev 就直接挂了。
 */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
class MindforgeAiApplicationTests {

    @Test
    void contextLoads() {
    }

}
