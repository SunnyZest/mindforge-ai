package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 会话详情 = 会话基本信息 + 完整消息列表。
 * <p>
 * 直接继承 {@link ConversationVO} 而不是嵌套一个字段 ——
 * 前端拿到的就是「会话 + messages」，少一层解包。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "会话详情")
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationDetailVO extends ConversationVO {

    @Schema(description = "消息列表，按时间正序")
    private List<MessageVO> messages;
}
