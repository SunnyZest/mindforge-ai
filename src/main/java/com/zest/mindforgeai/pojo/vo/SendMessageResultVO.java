package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 发送消息的结果，对齐文档 §十八。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "发送消息结果")
@Data
public class SendMessageResultVO {

    @Schema(description = "助手回复的消息 id —— 反馈接口要用它")
    private String messageId;

    @Schema(description = "回答内容")
    private String answer;

    @Schema(description = "引用的来源")
    private List<SourceVO> sources;

    /**
     * ⚠️ 这两个字段的语义不一样，前端别混用（AI 服务那边也这么强调）：
     * <ul>
     *   <li>{@code hasContext} —— 有没有检索到相关片段。展示「参考了 N 个片段」用它</li>
     *   <li>{@code hasAnswer} —— 模型有没有给出实质回答。判断「要不要显示答案」用它</li>
     * </ul>
     * 两者都为 true 是常态。但 {@code hasContext=true + hasAnswer=false} 也常见 ——
     * 检索到了片段（相似度够高），但片段里没有用户问的那个信息，
     * 模型如实回答「资料里没有」。这时 sources 里有内容，但那些内容答不了这个问题。
     */
    @Schema(description = "是否检索到了片段（≠ 有答案）")
    private Boolean hasContext;

    @Schema(description = "模型是否给出了实质回答")
    private Boolean hasAnswer;
}
