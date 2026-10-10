package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 内部接口：下发给 Python 的提示词（含正文）。
 *
 * <p>和用户端的 {@code PromptOptionVO} 的区别就一条：<b>这个带 content</b>。
 * 真正要渲染提示词的是 Python 服务，它必须拿到正文 ——
 * 所以单独开一个视图，只在 {@code /internal/**}（服务令牌保护）下暴露。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "提示词运行时配置（含正文，仅内部接口）")
public record PromptRuntimeVO(

        @Schema(description = "版本行 id")
        String id,

        @Schema(description = "场景")
        String scene,

        @Schema(description = "变体名")
        String name,

        @Schema(description = "版本号")
        Integer version,

        @Schema(description = "给用户看的选项名")
        String label,

        @Schema(description = "是否该场景的默认")
        Boolean isDefault,

        @Schema(description = "是否允许用户切换")
        Boolean userSelectable,

        @Schema(description = "提示词正文")
        String content
) {
}
