package com.zest.mindforgeai.common.constant;

/**
 * 登录会话相关常量。
 *
 * @author crx
 * @since 2026-10-30
 */
public final class SessionConstants {

    private SessionConstants() {
    }

    /** 用户端（求职者入口，/api/app/**） */
    public static final String END_APP = "APP";

    /** 管理端（运营后台，/api/admin/**） */
    public static final String END_ADMIN = "ADMIN";

    /**
     * UA 原文最长保留多少字符。
     * <p>
     * 对齐 {@code sys_refresh_token.user_agent varchar(512)}。真实 UA 很少超过 200，
     * 但请求头是客户端随便写的 —— 不截断会直接撞「Data too long」把登录搞失败。
     */
    public static final int USER_AGENT_MAX_LEN = 512;

    /**
     * 请求属性名：当前请求所属的会话 id。
     * <p>
     * 由 {@code JwtAuthenticationFilter} 解析 token 时写入，控制器通过
     * {@code SecurityUtils.getCurrentSessionId()} 读取。
     * 存在的意义：让「我的登录设备」接口能标出哪一条是「本机」，
     * 而不用在控制器里再把 token 解析一遍。
     */
    public static final String SESSION_ID_ATTR = "mf.sessionId";

    /**
     * token 作废原因。
     * <p>
     * ⚠️ <b>这几个值不是给人看的，是给 refresh() 判断「要不要按泄露处理」用的。</b>
     * 只有 {@link #REVOKE_ROTATED} 才触发「撤销该用户全部会话」的重放检测；
     * 其余原因（登出 / 被踢）只是拒绝这一次刷新 ——
     * 否则「在 A 设备踢掉 B」会导致 B 一刷新就把 A 也带下线。
     */
    public static final class RevokeReason {

        private RevokeReason() {
        }

        /** 轮转：正常刷新后旧 token 作废。这是唯一会被判定为「泄露」的原因 */
        public static final String ROTATED = "ROTATED";

        /** 用户主动登出 */
        public static final String LOGOUT = "LOGOUT";

        /** 被其他设备踢掉 */
        public static final String KICKED = "KICKED";

        /** 点了「退出其他设备」被清掉 */
        public static final String REVOKE_OTHERS = "REVOKE_OTHERS";

        /** 全量撤销（重放检测、改密码等） */
        public static final String REVOKE_ALL = "REVOKE_ALL";
    }
}
