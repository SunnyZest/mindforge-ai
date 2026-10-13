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
 * 简历表
 * </p>
 *
 * @author crx
 * @since 2026-10-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("resume")
@Schema(description = "Resume对象,简历表")
public class Resume implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属用户 id")
    private Long userId;

    /**
     * 原始文件 id。
     * <p>
     * 表注释里写了「手工录入时为空」—— 手工录入这条路还没做，
     * 目前上传简历必带文件，所以这个字段实际都有值。
     */
    @Schema(description = "原始文件 id（逻辑关联 sys_file.id）")
    private Long fileId;

    @Schema(description = "简历名称")
    private String name;

    @Schema(description = "目标职位")
    private String targetPosition;

    @Schema(description = "简历摘要（AI 提炼）")
    private String summary;

    @Schema(description = "原始文本内容（解析后提取）")
    private String rawContent;

    /** 同 Job.structuredJson：库里是 JSON 列，这里按 String 接收 */
    @Schema(description = "结构化后的简历数据（AI 解析结果）")
    private String structuredJson;

    @Schema(description = "解析状态：PENDING / PARSING / SUCCESS / FAILED")
    private String parseStatus;

    /**
     * 解析失败原因，成功时为 null。
     * <p>
     * 存的是<b>给人看的一句话</b>，不是日志 —— 写入前会截断到 500 字符
     * （列宽就是 500），完整堆栈在服务日志里。
     */
    @Schema(description = "解析失败原因（最近一次）；成功时为 null")
    private String errorMessage;

    @Schema(description = "状态：1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "逻辑删除：0 未删除 / 1 已删除")
    @TableLogic
    private Integer deleted;
}
