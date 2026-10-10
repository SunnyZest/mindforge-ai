package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建 AI 面试，文档 §二十二 {@code POST /api/app/interviews}。
 * <p>
 * ⚠️ 这个接口<b>只创建一行数据，不出题</b>（毫秒级返回）。
 * 出题是单独一步（{@code POST /interviews/{id}/generate}，要几十秒）——
 * 拆开的原因：创建失败和 AI 失败是两类问题，混在一起前端说不清，
 * 而且出题失败后用户没法重试（除非再建一个）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Schema(description = "创建 AI 面试")
@Data
public class InterviewCreateDTO {

    @Schema(description = "基于哪份简历出题，可空")
    private String resumeId;

    @Schema(description = "针对哪个岗位出题，可空")
    private String jobId;

    @Schema(description = "面试标题；不传则按简历/岗位自动取一个")
    @Size(max = 255, message = "面试标题最长 255 个字符")
    private String title;

    @Schema(description = "面试类型：TECHNICAL / BEHAVIORAL / MIXED，默认 TECHNICAL",
            allowableValues = {"TECHNICAL", "BEHAVIORAL", "MIXED"})
    private String interviewType;

    @Schema(description = "难度：EASY / MEDIUM / HARD，默认 MEDIUM",
            allowableValues = {"EASY", "MEDIUM", "HARD"})
    private String difficulty;

    @Schema(description = "题目数量，默认 5")
    @Min(value = 1, message = "题目数量至少 1 道")
    @Max(value = 20, message = "题目数量最多 20 道")
    private Integer questionCount;
}
