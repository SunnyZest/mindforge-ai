package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建一个提示词版本。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "新建提示词版本")
public class PromptCreateDTO {

    @Schema(description = "场景：RAG_CHAT / RESUME_PARSE 等", example = "RAG_CHAT")
    @NotBlank(message = "场景不能为空")
    @Size(max = 32, message = "场景最长 32 个字符")
    private String scene;

    /**
     * ⚠️ 变体名 = 用户切换的单位。同一个 (scene, name) 再新建就是给它加一个新版本，
     * 而不是新建一个变体 —— 想加变体就换个 name。
     */
    @Schema(description = "变体名，如「默认」「严谨」", example = "默认")
    @NotBlank(message = "变体名不能为空")
    @Size(max = 64, message = "变体名最长 64 个字符")
    private String name;

    @Schema(description = "提示词正文（保留 {占位符} 形式）")
    @NotBlank(message = "提示词正文不能为空")
    private String content;

    /**
     * ⚠️ 是否出现在用户的切换列表里。
     * <p>
     * 8 个 prompt 里有 7 个的输出格式是<b>内部契约</b>（resume_parse 必须吐特定 JSON 等），
     * 让用户随便切会直接让解析/出题挂掉。所以由管理员逐条决定哪些能暴露给用户。
     */
    @Schema(description = "是否允许用户切换")
    private Boolean userSelectable;

    @Schema(description = "给用户看的选项名（userSelectable=true 时必填）", example = "严谨")
    @Size(max = 64, message = "选项名最长 64 个字符")
    private String label;

    @Schema(description = "是否作为该场景的默认（用户没指定时用这条）")
    private Boolean isDefault;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String description;
}
