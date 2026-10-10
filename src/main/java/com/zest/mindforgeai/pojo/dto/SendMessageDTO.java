package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送消息请求，对齐文档 §十八。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "发送消息")
@Data
public class SendMessageDTO {

    @Schema(description = "用户提问")
    @NotBlank(message = "消息内容不能为空")
    private String message;

    @Schema(description = "检索片段数，默认 8")
    @Min(value = 1, message = "topK 至少为 1")
    @Max(value = 50, message = "topK 最多为 50")
    private Integer topK = 8;

    /**
     * 是否重排序。
     * <p>
     * ⚠️ 目前<b>不生效</b> —— AI 服务还没接重排序模型，
     * 响应里会如实返回 {@code reranked: false}。留着字段是为了将来兼容，
     * 前端现在传什么都不影响结果。
     */
    @Schema(description = "是否重排序（暂不生效）")
    private Boolean enableRerank = false;

    /**
     * 回答风格 id（来自 {@code GET /app/prompts?scene=RAG_CHAT}）。
     * <p>
     * 可空 = 用管理员设的默认风格。传了但那条已经下线/不可选时，
     * 由 AI 服务回退到默认 —— 用户上一次选的风格被管理员下线了，
     * 不该让他的提问直接失败。
     */
    @Schema(description = "回答风格 id，可空 = 默认风格")
    private String promptId;
}
