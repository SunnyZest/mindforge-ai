package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 回答反馈请求，对齐文档 §十八。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "回答反馈")
@Data
public class RagFeedbackDTO {

    @Schema(description = "被反馈的消息 id")
    @NotBlank(message = "消息 id 不能为空")
    private String messageId;

    @Schema(description = "评价：UP 有帮助 / DOWN 没帮助")
    @NotBlank(message = "评价不能为空")
    @Pattern(regexp = "UP|DOWN", message = "评价只能是 UP 或 DOWN")
    private String rating;

    @Schema(description = "补充说明")
    @Size(max = 500, message = "说明不能超过 500 字")
    private String reason;
}
