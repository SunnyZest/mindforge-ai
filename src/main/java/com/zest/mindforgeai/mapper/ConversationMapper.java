package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话 Mapper
 *
 * @author crx
 * @since 2026-10-05
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
