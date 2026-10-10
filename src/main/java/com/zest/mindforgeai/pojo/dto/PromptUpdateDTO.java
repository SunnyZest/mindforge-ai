package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改一个<b>草稿</b>版本。
 *
 * <p>⚠️ 已发布（PUBLISHED）的版本不允许修改 —— 要改就新建一个版本。
 * 这条看着不方便，但它是版本管理能成立的前提：允许原地改的话，
 * 回滚回去的「旧版」其实是被改过的内容，历史就不可信了。
 *
 * <p>字段都是可选的：只传要改的那个。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "修改提示词草稿")
public class PromptUpdateDTO {

    @Schema(description = "提示词正文")
    private String content;

    @Schema(description = "是否允许用户切换")
    private Boolean userSelectable;

    @Schema(description = "给用户看的选项名")
    @Size(max = 64, message = "选项名最长 64 个字符")
    private String label;

    @Schema(description = "是否作为该场景的默认")
    private Boolean isDefault;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String description;
}
