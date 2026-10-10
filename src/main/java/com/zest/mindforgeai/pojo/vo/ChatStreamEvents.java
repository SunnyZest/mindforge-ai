package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 流式对话（SSE）推给前端的四种事件体。
 * <p>
 * <b>为什么不拆成四个文件</b>：它们是一份协议，放一起才能一眼看全 ——
 * 前端要按这四种事件写渲染逻辑，散在四个文件里反而难对照。
 * <p>
 * <b>接口契约</b>（{@code POST /api/app/conversations/{id}/messages/stream}）：
 * <pre>
 * event: sources   data: {"sources":[…],"hasContext":true,"contextTruncated":false}
 * event: delta     data: {"text":"订单中心主要是…"}        ← 很多个，边收边渲染
 * event: done      data: {"messageId":"12","answer":"完整回答","hasAnswer":true}
 * event: error     data: {"code":50202,"message":"…","traceId":"…"}   ← 代替 done
 * </pre>
 * ⚠️ <b>{@code done} 和 {@code error} 只会来一个</b>，前端两个都要处理，
 * 收到 error 就别再等 done 了（否则会一直转圈）。
 * <p>
 * ⚠️ <b>HTTP 状态码是 200</b>（流一旦开始推，响应头就发出去了，改不了）——
 * 只有「会话不存在」这类<b>开流之前</b>的错误才会返回正常的 404 / 409。
 *
 * @author crx
 * @since 2026-10-10
 */
public final class ChatStreamEvents {

    private ChatStreamEvents() {
    }

    /**
     * 检索结果 —— 流的<b>第一个</b>事件。
     * <p>
     * 之所以不等回答完再一起给：检索在调模型之前就完成了，
     * 先推出去用户能立刻看到「参考了哪几段」，不用干等（实测首字节 0.45 秒，
     * 而完整回答要 8.7 秒）。
     */
    @Schema(description = "流式对话-引用来源事件")
    @Data
    public static class Sources {

        @Schema(description = "引用来源（和同步接口的 sources 结构一致）")
        private List<SourceVO> sources;

        @Schema(description = "是否检索到了资料。false 时后端不会调模型，直接返回兜底话术")
        private Boolean hasContext;

        @Schema(description = "参考资料是否被截断（片段太多，只取了一部分）")
        private Boolean contextTruncated;

        public static Sources of(List<SourceVO> sources, boolean hasContext,
                                 boolean contextTruncated) {
            Sources vo = new Sources();
            vo.setSources(sources == null ? List.of() : sources);
            vo.setHasContext(hasContext);
            vo.setContextTruncated(contextTruncated);
            return vo;
        }
    }

    /** 一段增量文本。前端把它追加到当前气泡里就行 */
    @Schema(description = "流式对话-增量文本事件")
    @Data
    public static class Delta {

        @Schema(description = "本次增量的文本（可能是半个词、半句话）")
        private String text;

        public static Delta of(String text) {
            Delta vo = new Delta();
            vo.setText(text);
            return vo;
        }
    }

    /**
     * 生成完成。
     * <p>
     * ⚠️ 这里再给一次完整回答，是让前端有机会用「最终版」覆盖掉自己拼的增量文本
     * （增量拼接偶尔会和最终结果差几个字）。{@code sources} 不重复给 ——
     * 第一个事件已经推过了。
     */
    @Schema(description = "流式对话-完成事件")
    @Data
    public static class Done {

        @Schema(description = "落库后的助手消息 id（给回答打分时要用它调 /rag/feedback）")
        private String messageId;

        @Schema(description = "完整回答")
        private String answer;

        @Schema(description = "是否给出了实质回答。false 表示资料答不了，返回的是兜底话术")
        private Boolean hasAnswer;

        public static Done of(String messageId, String answer, boolean hasAnswer) {
            Done vo = new Done();
            vo.setMessageId(messageId);
            vo.setAnswer(answer);
            vo.setHasAnswer(hasAnswer);
            return vo;
        }
    }

    /**
     * 出错 —— <b>代替 {@code done}</b>。
     * <p>
     * ⚠️ 可能是「吐到一半才失败」：前端已经渲染了部分文本，
     * 这时 {@code message} 里会说「已输出的部分可能不完整」，
     * 页面上要保留那半截文本并明确标注，别当成完整答案。
     */
    @Schema(description = "流式对话-错误事件")
    @Data
    public static class Error {

        @Schema(description = "错误码，和统一信封是同一套（40001 / 50201 / 50202 / 50401…）")
        private Integer code;

        @Schema(description = "给用户看的一句话")
        private String message;

        @Schema(description = "链路 id —— 报问题时带上它，能直接在日志里定位这次调用")
        private String traceId;

        public static Error of(int code, String message, String traceId) {
            Error vo = new Error();
            vo.setCode(code);
            vo.setMessage(message);
            vo.setTraceId(traceId);
            return vo;
        }
    }
}
