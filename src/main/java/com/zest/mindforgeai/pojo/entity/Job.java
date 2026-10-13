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
 * 职位 JD 表
 * </p>
 *
 * @author crx
 * @since 2026-10-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("job")
@Schema(description = "Job对象,职位 JD 表")
public class Job implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属用户 id")
    private Long userId;

    @Schema(description = "职位名称")
    private String title;

    @Schema(description = "公司名称")
    private String companyName;

    @Schema(description = "工作地点")
    private String location;

    @Schema(description = "职位来源链接")
    private String sourceUrl;

    @Schema(description = "JD 原始文本")
    private String rawContent;

    /**
     * AI 解析出的结构化数据。
     * <p>
     * 库里是 JSON 列，这里按 String 接收（JDBC 返回 JSON 文本），
     * 需要嵌套 JSON 时在 Service 层转成 JsonNode —— 见 JobDetailVO 的说明。
     */
    @Schema(description = "结构化后的 JD 数据（AI 解析结果）")
    private String structuredJson;

    @Schema(description = "经验要求，如 3-5 年")
    private String experienceRequired;

    @Schema(description = "学历要求，如 本科")
    private String educationRequired;

    @Schema(description = "解析状态：PENDING / PARSING / SUCCESS / FAILED")
    private String parseStatus;

    @Schema(description = "投递阶段：WISHLIST/APPLIED/SCREENING/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED")
    private String applicationStage;

    @Schema(description = "绑定的目标简历 id")
    private Long linkedResumeId;

    @Schema(description = "薪资范围或 Offer 年薪包")
    private String salaryOffer;

    @Schema(description = "下一场笔试/面试提醒时间")
    private LocalDateTime nextActionAt;

    @Schema(description = "求职跟进备注")
    private String crmNotes;

    /** 解析失败原因，成功时为 null。理由同 {@link Resume#getErrorMessage()} */
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
