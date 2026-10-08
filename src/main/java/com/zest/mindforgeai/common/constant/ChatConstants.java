package com.zest.mindforgeai.common.constant;

/**
 * 对话相关的常量。
 *
 * @author crx
 * @since 2026-10-08
 */
public final class ChatConstants {

    private ChatConstants() {
    }

    /** 用户消息 */
    public static final String ROLE_USER = "USER";
    /** 助手（模型）消息 */
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    /** 系统提示（暂未落库，留作将来需要存 system prompt 时用） */
    public static final String ROLE_SYSTEM = "SYSTEM";

    /** 反馈：有帮助 */
    public static final String RATING_UP = "UP";
    /** 反馈：没帮助 */
    public static final String RATING_DOWN = "DOWN";

    /**
     * 拼进 prompt 的历史轮数上限。
     * <p>
     * 一轮 = 一问一答，所以实际最多带 {@code 2 × MAX_HISTORY_ROUNDS} 条消息。
     * 取多了会挤占参考资料的空间，而历史对回答质量的帮助有限。
     * 这个值要和 AI 服务侧的 {@code max_history_rounds} 保持一致。
     */
    public static final int MAX_HISTORY_ROUNDS = 5;
}
