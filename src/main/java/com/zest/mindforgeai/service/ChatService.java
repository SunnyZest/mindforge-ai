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

    /** 只检索，不生成回答、不落库 */
    List<SourceVO> search(RagSearchDTO dto);

    /** 给某条回答打分 */
    void feedback(RagFeedbackDTO dto);
}
