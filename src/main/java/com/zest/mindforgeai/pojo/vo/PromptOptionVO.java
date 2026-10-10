package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户端：一个可选的回答风格。
 *
 * <p>⚠️ <b>刻意不带 content</b>。用户只需要知道「有哪些风格、各自什么特点」，
 * 不该看到提示词原文 —— 原文里有防虚构规则和输出格式约定，
 * 暴露出去等于把提示词送人（也容易被拿来绕过约束）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "可选的回答风格")
public class PromptOptionVO {

    @Schema(description = "提交时传这个 id")
    private String id;

    @Schema(description = "给用户看的名字，如「严谨」「简洁」")
    private String label;

    @Schema(description = "一句话说明这个风格的特点")
    private String description;

    @Schema(description = "是否是默认风格（用户没选时用的就是它）")
    private Boolean isDefault;
}
