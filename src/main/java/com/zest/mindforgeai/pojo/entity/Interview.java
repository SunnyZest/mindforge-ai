package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * AI 面试表
 * </p>
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("interview")
@Schema(description = "Interview对象,AI 面试表")
public class Interview implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "归属用户 id")
    private Long userId;

    /**
     * 关联简历，可空。
     * <p>
     * 可空是刻意的：允许「不基于简历的通用面试」。
     * 有值时出题会带上简历原文，题目会贴着候选人自己的经历问。
     */
    @Schema(description = "关联简历 id（逻辑关联 resume.id），可空")
    private Long resumeId;

    @Schema(description = "关联职位 id（逻辑关联 job.id），可空")
    private Long jobId;

    @Schema(description = "面试标题")
    private String title;

    @Schema(description = "面试类型：TECHNICAL / BEHAVIORAL / MIXED")
    private String interviewType;

    @Schema(description = "难度：EASY / MEDIUM / HARD")
    private String difficulty;

    /**
     * 状态：CREATED / IN_PROGRESS / FINISHED。
     * <p>
     * ⚠️ 它<b>不表示</b>「出题好了没」—— 出题是否完成看 {@link #questionCount}。
     * 出题失败时面试会停在 {@code CREATED} 且题目数为 0，重新调出题接口即可
     * （出题是幂等的：已有题目会先清后出）。
     */
    @Schema(description = "状态：CREATED / IN_PROGRESS / FINISHED")
    private String status;

    @Schema(description = "题目数量。0 表示还没出题（或出题失败）")
    private Integer questionCount;

    @Schema(description = "开始作答时间")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间")
    private LocalDateTime finishedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "逻辑删除：0 未删除 / 1 已删除")
    @TableLogic
    private Integer deleted;
}
