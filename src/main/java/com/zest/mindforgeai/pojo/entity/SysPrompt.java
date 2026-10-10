package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 提示词配置。
 *
 * <p>⚠️ 这张表<b>没有 deleted 列</b>，删除是物理删 —— 和历史版本表一致：
 * 留着一条谁也看不到的草稿没有意义，反而会让「这个变体到底有几版」变得含糊。
 *
 * <p>★ <b>三层结构，别混</b>：
 * <ul>
 *   <li>{@code scene + name} —— 一个<b>变体</b>（用户切换的单位）</li>
 *   <li>{@code version} —— 该变体的<b>历史版本</b>（发布/回滚的单位）</li>
 * </ul>
 * 之所以是两层而不是一层：原设计只有「一个 prompt 的多个版本」，
 * 但「供用户切换」要求同一个场景下有<b>多个并存的变体</b> ——
 * 那是不同的变体，不是同一个的旧版本。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_prompt")
@Schema(description = "SysPrompt对象,提示词配置")
public class SysPrompt implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "场景：RAG_CHAT / RESUME_PARSE 等（大写）")
    private String scene;

    @Schema(description = "变体名，如「默认」「严谨」")
    private String name;

    @Schema(description = "该变体的版本号，从 1 开始")
    private Integer version;

    @Schema(description = "提示词正文")
    private String content;

    @Schema(description = "DRAFT / PUBLISHED / ARCHIVED")
    private String status;

    @Schema(description = "该 scene 下用户没指定时用这条")
    private Integer isDefault;

    @Schema(description = "是否出现在用户的切换列表里")
    private Integer userSelectable;

    @Schema(description = "给用户看的选项名")
    private String label;

    @Schema(description = "备注")
    private String description;

    @Schema(description = "创建人（管理员）id")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
