package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建角色，文档 §十四
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "创建角色")
@Data
public class RoleCreateDTO {

    @Schema(description = "角色名称", example = "内容管理员")
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称最长 64 个字符")
    private String name;

    /**
     * code 一旦创建就不允许修改 —— 权限判定、代码里的 {@code hasRole('ADMIN')}
     * 都依赖它，改了会静默失效。所以这里限制成「大写字母开头 + 大写字母/数字/下划线」。
     */
    @Schema(description = "角色 code，仅大写字母/数字/下划线，且以大写字母开头", example = "CONTENT_ADMIN")
    @NotBlank(message = "角色 code 不能为空")
    @Size(max = 64, message = "角色 code 最长 64 个字符")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
            message = "角色 code 只能包含大写字母、数字、下划线，且必须以大写字母开头")
    private String code;

    @Schema(description = "描述", example = "管理知识库和文档")
    @Size(max = 255, message = "描述最长 255 个字符")
    private String description;
}
