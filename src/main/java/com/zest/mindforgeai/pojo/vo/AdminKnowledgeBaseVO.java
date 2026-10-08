package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端知识库列表项。
 * <p>
 * 与用户端的 {@link KnowledgeBaseVO} 的差别只有一个：<b>多了归属人</b>。
 * 管理员看的是所有人的知识库，列表里不显示「这是谁的」就没法用；
 * 而用户端看的是自己的，显示归属人是多余的（还等于把自己的 id 反复吐出去）。
 * <p>
 * 所以刻意分成两个 VO，而不是给 KnowledgeBaseVO 加两个字段 ——
 * 后者会让用户端接口也跟着多返回 ownerId / ownerUsername。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "管理端知识库列表项")
@Data
public class AdminKnowledgeBaseVO {

    @Schema(description = "知识库 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "归属用户 id")
    private String userId;

    @Schema(description = "归属用户名")
    private String username;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "1 正常 / 0 禁用")
    private Integer status;

    @Schema(description = "文档数（实时统计，不是表里的冗余字段）")
    private Integer documentCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
