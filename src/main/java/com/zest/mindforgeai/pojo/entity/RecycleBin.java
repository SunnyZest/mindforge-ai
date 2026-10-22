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
 * 回收站：逻辑删除后的恢复入口。
 *
 * <p>★ <b>为什么只存「丢失的部分」而不是整行快照</b>：
 * 逻辑删除的行还在原表里（只是 {@code deleted=1}），恢复时把它改回 0 就回来了，
 * 整行快照是重复存储。真正找不回来的是<b>删除时被物理清掉的关联数据</b>
 * （简历的技能与匹配分析、文档的向量 chunk）—— 那才是 {@code payloadJson} 要装的东西。
 * 一期这五种资源（职位 / 日程 / Offer / 面试 / 对话）删除时都没有物理清理，
 * 所以 payload 全为 NULL。
 *
 * <p>★ <b>这张表没有 deleted 列</b>：回收站记录本身不需要再被逻辑删除，
 * 恢复或彻底删除后直接物理删掉即可（MyBatis-Plus 的全局逻辑删除按字段名生效，
 * 实体没有该属性就不拦截，{@code removeById} 走物理删 —— 正是我们要的）。
 *
 * @author crx
 * @since 2026-10-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("recycle_bin")
@Schema(description = "回收站记录")
public class RecycleBin implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "删除者 user.id")
    private Long userId;

    @Schema(description = "资源类型：JOB / JOB_EVENT / OFFER / INTERVIEW / CONVERSATION")
    private String resourceType;

    @Schema(description = "原表 id —— 原行还在，只是 deleted=1")
    private Long resourceId;

    @Schema(description = "展示名，删除后原行查不到也能显示")
    private String title;

    @Schema(description = "被物理删掉、无法从原表找回的关联数据；一期资源都没有，为 null")
    private String payloadJson;

    @Schema(description = "删除时间")
    private LocalDateTime deletedAt;

    @Schema(description = "到期时间，过了就不能再恢复")
    private LocalDateTime expireAt;
}
