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

    /**
     * 归属人昵称。
     * <p>
     * 和 {@link #username} 一起返回的原因：管理员认人靠昵称（用户注册时填的那个），
     * 排查时只看 username（登录名）经常对不上号。没有昵称的账号为 null。
     */
    @Schema(description = "归属人昵称，未设置为 null")
    private String ownerNickname;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "1 正常 / 0 禁用")
    private Integer status;

    @Schema(description = "文档数（实时统计，不是表里的冗余字段）")
    private Integer documentCount;

    /**
     * 库内文档的总字节数（{@code document.file_size} 求和，只算 {@code deleted = 0}）。
     * <p>
     * 用途：管理端排查「哪个库把存储吃掉了」。注意它统计的是<b>文档记录</b>的大小，
     * 与 {@code sys_file} 的实际对象大小可能有细微差异（同一文件被多个库引用时会重复计）。
     */
    @Schema(description = "库内文档总字节数")
    private Long totalBytes;

    @Schema(description = "最近一次上传文档的时间，空库为 null")
    private LocalDateTime lastDocumentAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "是否系统官方全局智库：0 否 / 1 是")
    private Integer isSystem;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
