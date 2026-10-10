package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.SysPrompt;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端：一个提示词<b>版本</b>。
 *
 * <p>⚠️ 这是管理端视图，<b>带正文</b>。用户端不能用它 ——
 * 用户只需要看到「有哪些风格可选」，不该看到提示词原文
 * （原文里有防虚构规则、输出格式约定这些东西，暴露出去等于把提示词送人）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "提示词版本")
public class PromptVO {

    @Schema(description = "版本行 id")
    private String id;

    @Schema(description = "场景")
    private String scene;

    @Schema(description = "变体名")
    private String name;

    @Schema(description = "版本号")
    private Integer version;

    @Schema(description = "提示词正文")
    private String content;

    @Schema(description = "DRAFT / PUBLISHED / ARCHIVED")
    private String status;

    @Schema(description = "是否该场景的默认")
    private Boolean isDefault;

    @Schema(description = "是否允许用户切换")
    private Boolean userSelectable;

    @Schema(description = "给用户看的选项名")
    private String label;

    @Schema(description = "备注")
    private String description;

    @Schema(description = "创建人 id")
    private String createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static PromptVO of(SysPrompt entity) {
        PromptVO vo = new PromptVO();
        vo.setId(String.valueOf(entity.getId()));
        vo.setScene(entity.getScene());
        vo.setName(entity.getName());
        vo.setVersion(entity.getVersion());
        vo.setContent(entity.getContent());
        vo.setStatus(entity.getStatus());
        vo.setIsDefault(Integer.valueOf(1).equals(entity.getIsDefault()));
        vo.setUserSelectable(Integer.valueOf(1).equals(entity.getUserSelectable()));
        vo.setLabel(entity.getLabel());
        vo.setDescription(entity.getDescription());
        vo.setCreatedBy(entity.getCreatedBy() == null ? null : String.valueOf(entity.getCreatedBy()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
