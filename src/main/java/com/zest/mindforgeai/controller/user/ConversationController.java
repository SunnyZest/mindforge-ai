package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.CreateConversationDTO;
import com.zest.mindforgeai.pojo.dto.SendMessageDTO;
import com.zest.mindforgeai.pojo.vo.ConversationDetailVO;
import com.zest.mindforgeai.pojo.vo.ConversationVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.SendMessageResultVO;
import com.zest.mindforgeai.service.ChatService;
import com.zest.mindforgeai.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final ConversationService conversationService;
    private final ChatService chatService;

    public ConversationController(ConversationService conversationService,
                                  ChatService chatService) {
        this.conversationService = conversationService;
        this.chatService = chatService;
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
     * 发消息 —— 核心接口：检索知识库 + 生成回答 + 落库。
     * <p>
     * ⚠️ 这个接口是<b>同步阻塞</b>的，大模型生成完整回答通常要几秒到几十秒。
     * 前端要设置足够的超时，并给出「正在思考」的反馈。
     * 流式版本（{@code /messages/stream}）还没做，见 PLAN.md。
     */
    @Operation(summary = "发送消息并获取回答")
    @PostMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('app:conversation:write')")
    public Result<SendMessageResultVO> sendMessage(@PathVariable Long id,
                                                   @Valid @RequestBody SendMessageDTO dto) {
        return Result.success(chatService.sendMessage(id, dto));
    }
}
