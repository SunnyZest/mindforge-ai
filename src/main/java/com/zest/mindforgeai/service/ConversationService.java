package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.CreateConversationDTO;
import com.zest.mindforgeai.pojo.entity.Conversation;
import com.zest.mindforgeai.pojo.vo.ConversationDetailVO;
import com.zest.mindforgeai.pojo.vo.ConversationVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * 会话管理。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface ConversationService {

    /** 新建会话 */
    ConversationVO create(CreateConversationDTO dto);

    /** 当前用户的会话列表 */
    PageResultVO<ConversationVO> page(long page, long pageSize);

    /** 会话详情（含完整消息列表） */
    ConversationDetailVO detail(Long conversationId);

    /**
     * 删除会话。
     * <p>
     * 会话是逻辑删除，但它的<b>消息和引用是物理删除</b> ——
     * chat_message / chat_source 都没有 deleted 列，跟会话一起清掉，不留历史。
     */
    void delete(Long conversationId);

    /**
     * 取会话并校验归属，不属于当前用户时抛「不存在」（不抛 403，避免泄漏 id 是否存在）。
     * <p>
     * 给发消息等内部逻辑复用。
     */
    Conversation requireOwned(Long conversationId);
}
