package com.zest.mindforgeai.pojo.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.pojo.entity.Job;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 职位 JD 详情。
 * <p>
 * 比列表项多出 {@code sourceUrl}、{@code rawContent} 和 {@code structuredJson} ——
 * 这三个都只在这一条记录上才有意义，放列表里纯属浪费带宽。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "职位 JD 详情")
@Data
public class JobDetailVO {

    @Schema(description = "职位 id")
    private String id;

    @Schema(description = "职位名称")
    private String title;

    @Schema(description = "公司名称")
    private String companyName;

    @Schema(description = "工作地点")
    private String location;

    @Schema(description = "职位来源链接")
    private String sourceUrl;

    @Schema(description = "经验要求")
    private String experienceRequired;

    @Schema(description = "学历要求")
    private String educationRequired;

    @Schema(description = "JD 正文（原始描述）")
    private String rawContent;

    /**
     * AI 解析出的结构化数据。
     * <p>
     * 用 {@link JsonNode} 而不是 String：数据库里是 JSON 列，直接当字符串返回的话
     * 前端收到的是一个<b>被转义的 JSON 字符串</b>（"{\"skills\":[...]}"），
     * 还得再 parse 一次。用 JsonNode 就是正常的嵌套 JSON。
     */
    @Schema(description = "AI 解析出的结构化数据（未解析时为 null）")
    private JsonNode structuredJson;

    @Schema(description = "解析状态")
    private String parseStatus;

    @Schema(description = "解析失败原因；非 FAILED 状态时为 null")
    private String errorMessage;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static JobDetailVO of(Job job, JsonNode structuredJson) {
        JobDetailVO vo = new JobDetailVO();
        vo.setId(job.getId() == null ? null : String.valueOf(job.getId()));
        vo.setTitle(job.getTitle());
        vo.setCompanyName(job.getCompanyName());
        vo.setLocation(job.getLocation());
        vo.setSourceUrl(job.getSourceUrl());
        vo.setExperienceRequired(job.getExperienceRequired());
        vo.setEducationRequired(job.getEducationRequired());
        vo.setRawContent(job.getRawContent());
        vo.setStructuredJson(structuredJson);
        vo.setParseStatus(job.getParseStatus());
        vo.setErrorMessage(job.getErrorMessage());
        vo.setStatus(job.getStatus());
        vo.setCreatedAt(job.getCreatedAt());
        vo.setUpdatedAt(job.getUpdatedAt());
        return vo;
    }
}
