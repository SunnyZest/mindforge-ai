package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交单题回答，文档 §二十二 {@code POST /api/app/interviews/{id}/answers}。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "提交面试单题回答")
@Data
public class InterviewAnswerDTO {

    @Schema(description = "题目 id（详情接口里每道题的 id）")
    @NotBlank(message = "题目 id 不能为空")
    private String questionId;

    /**
     * 回答内容，可空。
     * <p>
     * ⚠️ <b>允许空串</b>，而且必须允许 —— 跳过一道题是正常行为，
     * 空回答会拿到一个低分而不是报错。报错的话用户跳过一题整场面试就卡死了。
     */
    @Schema(description = "回答内容；可为空 —— 跳过一题是正常行为，会给低分而不是报错")
    @Size(max = 10000, message = "回答内容不能超过 10000 个字符")
    private String answer;
}
