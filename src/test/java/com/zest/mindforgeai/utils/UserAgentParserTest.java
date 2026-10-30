package com.zest.mindforgeai.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UA 粗解析。
 * <p>
 * 这是纯函数，不用起 Spring —— 但它有两个容易写错的地方值得钉住：
 * 判断顺序（Edge 里含 Chrome、Chrome 里含 Safari）和超长截断。
 *
 * @author crx
 * @since 2026-10-30
 */
@DisplayName("User-Agent 解析")
class UserAgentParserTest {

    private static final String CHROME_WIN =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    @Test
    @DisplayName("Chrome 的 UA 里也有 Safari，不能认成 Safari")
    void chromeIsNotSafari() {
        assertThat(UserAgentParser.describe(CHROME_WIN)).isEqualTo("Chrome · Windows");
    }

    @Test
    @DisplayName("Edge 的 UA 里也有 Chrome，必须先判 Edge")
    void edgeIsNotChrome() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";
        assertThat(UserAgentParser.describe(ua)).isEqualTo("Edge · Windows");
    }

    @Test
    @DisplayName("手机与微信")
    void mobile() {
        String wechatIos = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.44";
        assertThat(UserAgentParser.describe(wechatIos)).isEqualTo("微信 · iPhone");

        String android = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
        assertThat(UserAgentParser.describe(android)).isEqualTo("Chrome · Android");
    }

    @Test
    @DisplayName("空 UA / 认不出来：返回「未知设备」，不能返回 null 或空串")
    void unknown() {
        assertThat(UserAgentParser.describe(null)).isEqualTo("未知设备");
        assertThat(UserAgentParser.describe("")).isEqualTo("未知设备");
        assertThat(UserAgentParser.describe("完全认不出来的字符串")).isEqualTo("未知设备");
    }

    @Test
    @DisplayName("超长 UA 必须截断，否则 varchar(512) 会抛 Data too long")
    void truncateOverlong() {
        String huge = "Mozilla/5.0 " + "x".repeat(5000);
        String truncated = UserAgentParser.truncate(huge);
        assertThat(truncated).hasSize(512);
        // 没超长的原样返回
        assertThat(UserAgentParser.truncate(CHROME_WIN)).isEqualTo(CHROME_WIN);
        assertThat(UserAgentParser.truncate(null)).isNull();
    }
}
