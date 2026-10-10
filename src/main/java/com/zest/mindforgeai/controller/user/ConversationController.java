package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.context.TraceIdHolder;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.CreateConversationDTO;
import com.zest.mindforgeai.pojo.dto.SendMessageDTO;
import com.zest.mindforgeai.pojo.vo.ChatStreamEvents;
import com.zest.mindforgeai.pojo.vo.ConversationDetailVO;
import com.zest.mindforgeai.pojo.vo.ConversationVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.SendMessageResultVO;
import com.zest.mindforgeai.pojo.vo.SourceVO;
import com.zest.mindforgeai.service.ChatService;
import com.zest.mindforgeai.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 用户端对话，路径对齐文档 §十八。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "用户端-对话")
@RestController
@RequestMapping("/app")
public class ConversationController {

    /**
     * 流式对话的单条连接超时。
     * <p>
     * 一段回答通常 10~60 秒（首字节几秒内就来了），180 秒留足余量。
     * ⚠️ 它和 {@code AiServiceClient} 的读超时（660 秒）不是一回事：
     * 那个是 Java 等 Python 的超时，这个是 Java 等<b>前端</b>的超时 ——
     * 前端更早放弃的话，用户会看到流断掉。
     */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private final ConversationService conversationService;
    private final ChatService chatService;
    private final ThreadPoolTaskExecutor chatStreamExecutor;

    public ConversationController(ConversationService conversationService,
                                  ChatService chatService,
                                  @Qualifier("chatStreamExecutor")
                                  ThreadPoolTaskExecutor chatStreamExecutor) {
        this.conversationService = conversationService;
        this.chatService = chatService;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @Operation(summary = "新建会话")
    @PostMapping("/conversations")
    @PreAuthorize("hasAuthority('app:conversation:write')")
    public Result<ConversationVO> create(@Valid @RequestBody CreateConversationDTO dto) {
        return Result.success(conversationService.create(dto));
    }

    @Operation(summary = "会话列表")
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('app:conversation:read')")
    public Result<PageResultVO<ConversationVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.success(conversationService.page(page, pageSize));
    }

    @Operation(summary = "会话详情（含消息列表）")
    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('app:conversation:read')")
    public Result<ConversationDetailVO> detail(@PathVariable Long id) {
        return Result.success(conversationService.detail(id));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('app:conversation:write')")
    public Result<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return Result.success();
    }

    /**
     * 发消息 —— 同步版：等回答完整生成再返回。
     * <p>
     * ⚠️ 这个接口是<b>同步阻塞</b>的，大模型生成完整回答通常要几秒到几十秒。
     * 前端要设置足够的超时，并给出「正在思考」的反馈。
     * <p>
     * 想要「打字机效果」用流式版 {@code POST /conversations/{id}/messages/stream}，
     * 两者落库的内容完全一致（同一套逻辑，只是推送方式不同）。
     */
    @Operation(summary = "发送消息并获取回答")
    @PostMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('app:conversation:write')")
    public Result<SendMessageResultVO> sendMessage(@PathVariable Long id,
                                                   @Valid @RequestBody SendMessageDTO dto) {
        return Result.success(chatService.sendMessage(id, dto));
    }

    /**
     * 发消息 —— 流式版（SSE）：检索结果和回答增量边生成边推。
     * <p>
     * 事件格式见 {@link ChatStreamEvents}。四个要点：
     * <ol>
     *   <li><b>先推 {@code sources}</b>：检索在调模型之前就完成了，用户能立刻看到
     *       「参考了哪几段」，不用干等（实测首字节 0.45 秒，完整回答要 8.7 秒）</li>
     *   <li><b>HTTP 状态码是 200</b>：流一旦开始推，响应头就发出去了，改不了。
     *       所以运行时的错误走 {@code error} 事件 —— <b>前端必须处理它</b>，
     *       否则出错了会一直转圈</li>
     *   <li><b>{@code done} 和 {@code error} 只会来一个</b></li>
     *   <li>只有<b>开流之前</b>的错误（会话不存在、没关联知识库）才返回正常的
     *       404 / 409，靠 {@link ChatService#checkStreamable} 在请求线程上先拦一道</li>
     * </ol>
     * ⚠️ <b>不能用浏览器的原生 {@code EventSource}</b> —— 它只支持 GET，
     * 而提问要带请求体。前端用 {@code fetch} + 读 {@code response.body} 的流式读取
     * （或 {@code @microsoft/fetch-event-source}）。
     * <p>
     * ⚠️ 另外：<b>别在 EventSource 那种「自动重连」的封装上用它</b> ——
     * 重连会把同一条消息再发一遍。
     */
    @Operation(summary = "发送消息（SSE 流式返回回答）")
    @PostMapping(value = "/conversations/{id}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('app:conversation:write')")
    public SseEmitter sendMessageStream(@PathVariable Long id,
                                        @Valid @RequestBody SendMessageDTO dto,
                                        HttpServletResponse response) {

        // ① 在【请求线程】上先校验。开流之后状态码就改不了了，
        //    「会话不存在」这类错误要能正常返回 404 / 409 而不是 200 + error 事件。
        chatService.checkStreamable(id);

        // ② 关掉中间层的缓冲。⚠️ 不设 X-Accel-Buffering 的话 nginx 会攒够一批才发，
        //    本地直连完全看不出，一上 nginx 流式就白做了（Python 侧也设了同样的头）。
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(() -> log.warn("SSE 连接超时 conversationId={}", id));
        emitter.onError(e -> log.debug("SSE 连接异常 conversationId={}：{}", id, e.toString()));

        // ③ ⚠️ 安全上下文是 ThreadLocal 的，异步线程里取不到 ——
        //    必须先在这里捕获、再带进异步任务，否则 service 里
        //    SecurityUtils.getUserId() 会当成「未登录」直接抛异常。
        //    （这是个很容易踩的坑：同步版跑得好好的，换成异步就 401 了。）
        SecurityContext context = SecurityContextHolder.getContext();

        // ⚠️ traceId 同理 —— 它存在 MDC 里，也是线程本地的。
        //    不捕获的话，error 事件里的 traceId 会是 null，日志行上也没有链路 id。
        String traceId = TraceIdHolder.get();

        // ④ 客户端断开后不再尝试推送（每段都试一次会刷一屏日志），
        //    但【继续读流】—— 回答还是要落库的：
        //    用户消息已经存了，答案不存会话就断了。
        boolean[] clientGone = {false};

        chatStreamExecutor.execute(() -> {
            SecurityContextHolder.setContext(context);
            TraceIdHolder.set(traceId);
            try {
                chatService.chatStream(id, dto, new ChatService.ChatStreamHandler() {

                    @Override
                    public void onSources(List<SourceVO> sources, boolean hasContext,
                                          boolean contextTruncated) {
                        send(emitter, "sources", ChatStreamEvents.Sources.of(
                                sources, hasContext, contextTruncated), clientGone);
                    }

                    @Override
                    public void onDelta(String text) {
                        send(emitter, "delta", ChatStreamEvents.Delta.of(text), clientGone);
                    }

                    @Override
                    public void onDone(String messageId, String answer, boolean hasAnswer) {
                        send(emitter, "done",
                                ChatStreamEvents.Done.of(messageId, answer, hasAnswer), clientGone);
                    }

                    @Override
                    public void onError(int code, String message) {
                        send(emitter, "error",
                                ChatStreamEvents.Error.of(code, message, TraceIdHolder.get()),
                                clientGone);
                    }
                });

            } catch (BizException e) {
                // 走到这里说明是【调 AI 过程中】的业务异常（超时、上游不可用…）——
                // 前面可能已经推过 delta 了，所以只能用 error 事件告知
                send(emitter, "error",
                        ChatStreamEvents.Error.of(e.getCode(), e.getMessage(), TraceIdHolder.get()),
                        clientGone);

            } catch (Exception e) {
                log.error("流式对话出现未预期错误 conversationId={}", id, e);
                send(emitter, "error", ChatStreamEvents.Error.of(
                        ErrorCode.SYSTEM_ERROR.getCode(), "服务内部异常，请稍后重试",
                        TraceIdHolder.get()), clientGone);

            } finally {
                completeQuietly(emitter);
                SecurityContextHolder.clearContext();
            }
        });

        return emitter;
    }

    /**
     * 推一个事件。
     * <p>
     * ⚠️ 客户端可能已经断开（用户关页面 / 前端超时）—— 那时 {@code send} 会抛异常。
     * 这里吞掉并打 DEBUG：它<b>不是服务端错误</b>，而且断开后每段增量都会再抛一次，
     * 打 ERROR 会把日志刷满（同 {@code GlobalExceptionHandler} 里
     * {@code AsyncRequestNotUsableException} 的处理思路）。
     */
    private void send(SseEmitter emitter, String event, Object data, boolean[] clientGone) {
        if (clientGone[0]) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            clientGone[0] = true;
            log.debug("SSE 推送失败（客户端多半已断开），后续事件不再推送，回答仍会落库：{}", e.toString());
        }
    }

    /** 结束流。⚠️ 客户端已经断开时 complete 也可能抛，同样按「客户端走了」处理 */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 关闭时客户端已断开：{}", e.toString());
        }
    }
}
