package com.zest.mindforgeai.utils;

import com.zest.mindforgeai.common.constant.SessionConstants;
import org.springframework.util.StringUtils;

/**
 * 把 User-Agent 粗解析成一句人话，比如「Chrome · Windows」。
 * <p>
 * <b>为什么不引 ua-parser 之类的库：</b>「我的登录设备」只需要让用户认出
 * 「这台是不是我」，认得出浏览器和系统就够了。引一个带正则大表的库，
 * 换来的是依赖体积和一堆用不上的字段 —— 不值得。
 * <p>
 * ⚠️ <b>解析结果只用于展示，绝不能用于鉴权判断。</b>UA 是客户端随便写的，
 * 伪造它零成本。设备识别的安全边界在 refresh token 上，不在这里。
 *
 * @author crx
 * @since 2026-10-30
 */
public final class UserAgentParser {

    private UserAgentParser() {
    }

    /**
     * @param userAgent 原始 UA，可为 null
     * @return 形如「Chrome · Windows」；解析不出来返回「未知设备」
     */
    public static String describe(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return "未知设备";
        }
        String ua = userAgent;

        String browser = detectBrowser(ua);
        String os = detectOs(ua);

        if (browser == null && os == null) {
            return "未知设备";
        }
        if (browser == null) {
            return os;
        }
        if (os == null) {
            return browser;
        }
        return browser + " · " + os;
    }

    /**
     * 判断顺序有讲究：先判更具体的，再判更宽泛的。
     * <p>
     * 典型的坑：Chrome 的 UA 里同时含 "Chrome" 和 "Safari"，Edge 里同时含
     * "Edg" 和 "Chrome"。顺序反了会把 Edge 认成 Chrome、把 Chrome 认成 Safari。
     */
    private static String detectBrowser(String ua) {
        if (ua.contains("Edg/") || ua.contains("Edge/")) {
            return "Edge";
        }
        if (ua.contains("MicroMessenger")) {
            return "微信";
        }
        if (ua.contains("OPR/") || ua.contains("Opera")) {
            return "Opera";
        }
        if (ua.contains("Firefox/")) {
            return "Firefox";
        }
        if (ua.contains("Chrome/")) {
            return "Chrome";
        }
        if (ua.contains("Safari/")) {
            return "Safari";
        }
        // 命令行 / 测试工具：留着反而有助于排查「这条会话是脚本建的」
        if (ua.startsWith("curl/") || ua.startsWith("Wget/")) {
            return "命令行工具";
        }
        if (ua.startsWith("Java/") || ua.startsWith("okhttp/") || ua.startsWith("python-")) {
            return "程序调用";
        }
        return null;
    }

    private static String detectOs(String ua) {
        if (ua.contains("iPhone")) {
            return "iPhone";
        }
        if (ua.contains("iPad")) {
            return "iPad";
        }
        if (ua.contains("Android")) {
            return "Android";
        }
        if (ua.contains("Windows")) {
            return "Windows";
        }
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) {
            return "macOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return null;
    }

    /**
     * 截断到库字段长度。
     * <p>
     * ⚠️ 必须截：请求头是客户端随便写的，一个 10KB 的 UA 会让
     * {@code user_agent varchar(512)} 直接抛「Data too long」，把登录搞失败。
     * 这是「用户能控制输入」的典型位置，别信任它的长度。
     */
    public static String truncate(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= SessionConstants.USER_AGENT_MAX_LEN
                ? userAgent
                : userAgent.substring(0, SessionConstants.USER_AGENT_MAX_LEN);
    }
}
