package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.RagFeedbackDTO;
import com.zest.mindforgeai.pojo.dto.RagSearchDTO;
import com.zest.mindforgeai.pojo.dto.SendMessageDTO;
import com.zest.mindforgeai.pojo.vo.SendMessageResultVO;
import com.zest.mindforgeai.pojo.vo.SourceVO;

import java.util.List;

/**
 * 对话与 RAG。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface ChatService {

    /**
     * 发消息：检索知识库 → 生成回答 → 落库（消息 + 引用）。
     *
     * @return 助手回复的消息 id、回答正文、引用来源
     */
    SendMessageResultVO sendMessage(Long conversationId, SendMessageDTO dto);

    /**
     * 流式提问的前置校验：会话存在、属于当前用户、有关联知识库。
     * <p>
     * ⚠️ <b>它要在 HTTP 请求线程上调用</b>，不是在推事件的异步线程里 ——
     * 因为 SSE 一旦开始推第一个事件，响应头就发出去了，状态码再也改不了
     * （只能返回 200 + error 事件）。开流之前先把「会话不存在」这类错误拦掉，
     * 前端才能拿到正常的 404 / 409。
     * <p>
     * 代价是这次校验在 {@link #chatStream} 里会再走一遍（多一次主键查询）——
     * 一次索引查询换「错误语义正确」，值得。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException 会话不存在（404）/ 没关联知识库（409）
     */
    void checkStreamable(Long conversationId);

    /**
     * 流式提问：边生成边回调，结束时把回答和引用落库。
     * <p>
     * ⚠️ <b>阻塞方法</b>，调用方要放到异步线程里跑（见 controller 里的说明）。
     * <p>
     * <b>落库时机</b>：
     * <ul>
     *   <li>用户消息 —— 调 AI 之前就存（和 {@link #sendMessage} 一致：
     *       用户确实发过这条消息，即使 AI 失败也该留下）</li>
     *   <li>助手消息 + 引用 —— 收到 {@code done} 时才存</li>
     *   <li>⚠️ <b>出错时【不存】助手消息</b> —— 半截回答存下来会和完整回答长得一模一样，
     *       之后还会被当成历史喂回模型。宁可丢掉，让用户重试</li>
     * </ul>
     *
     * @param handler 事件回调，会在<b>读取 AI 流的线程</b>上被调用
     */
    void chatStream(Long conversationId, SendMessageDTO dto, ChatStreamHandler handler);

    /**
     * 流式提问的事件回调。
     * <p>
     * 和 {@link com.zest.mindforgeai.client.AiServiceClient.ChatStreamHandler} 的区别：
     * 那个是「AI 服务返回了什么」，这个是「业务层处理完该告诉前端什么」——
     * 引用已经从向量库 id 翻译成了数据库 id，{@code done} 里也带上了落库后的消息 id。
     */
    interface ChatStreamHandler {

        /** 检索结果（流的第一个事件） */
        void onSources(List<SourceVO> sources, boolean hasContext, boolean contextTruncated);

        /** 一段增量文本 */
        void onDelta(String text);

        /** 生成完成，回答已落库 */
        void onDone(String messageId, String answer, boolean hasAnswer);

        /** 出错（代替 onDone）。⚠️ 此时前面的 delta 可能已经推出去了 */
        void onError(int code, String message);
    }

    /** 只检索，不生成回答、不落库 */
    List<SourceVO> search(RagSearchDTO dto);

    /** 给某条回答打分 */
    void feedback(RagFeedbackDTO dto);
}
