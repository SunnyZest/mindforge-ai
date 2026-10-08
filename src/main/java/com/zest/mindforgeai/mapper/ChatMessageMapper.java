package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息 Mapper。
 * <p>
 * chat_message 没有 deleted 列 —— 删会话时消息是<b>物理删除</b>，
 * 跟着会话一起清掉，不保留历史。
 *
 * @author crx
 * @since 2026-10-08
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
