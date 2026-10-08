package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.RagFeedbackDTO;
import com.zest.mindforgeai.pojo.dto.RagSearchDTO;
import com.zest.mindforgeai.pojo.vo.SourceVO;
import com.zest.mindforgeai.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端 RAG，路径对齐文档 §十八。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "用户端-RAG")
@RestController
@RequestMapping("/app/rag")
public class RagController {

    private final ChatService chatService;

    public RagController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 只检索，不调用大模型、不落库。
     * <p>
     * 用途：让用户在提问前先看看知识库里有没有相关内容，
     * 或者做「搜索」式的交互。比问答快得多，也不消耗模型额度。
     */
    @Operation(summary = "RAG 检索（不生成回答）")
    @PostMapping("/search")
    @PreAuthorize("hasAuthority('app:rag:search')")
    public Result<List<SourceVO>> search(@Valid @RequestBody RagSearchDTO dto) {
        return Result.success(chatService.search(dto));
    }

    @Operation(summary = "给回答打分")
    @PostMapping("/feedback")
    @PreAuthorize("hasAuthority('app:rag:feedback')")
    public Result<Void> feedback(@Valid @RequestBody RagFeedbackDTO dto) {
        chatService.feedback(dto);
        return Result.success();
    }
}
