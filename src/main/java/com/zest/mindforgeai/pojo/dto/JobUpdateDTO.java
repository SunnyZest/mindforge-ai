package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改职位 JD。
 * <p>
 * 所有字段都可空：<b>传 null 表示不修改该字段</b>（局部更新）。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "修改职位 JD")
@Data
public class JobUpdateDTO {

    @Schema(description = "职位名称")
    @Size(max = 255, message = "职位名称最长 255 个字符")
    private String title;

    @Schema(description = "公司名称")
    @Size(max = 255, message = "公司名称最长 255 个字符")
    private String companyName;

    @Schema(description = "工作地点")
    @Size(max = 255, message = "工作地点最长 255 个字符")
    private String location;

    @Schema(description = "职位来源链接")
    @Size(max = 1000, message = "来源链接最长 1000 个字符")
    @Pattern(regexp = "^$|^https?://.+", message = "来源链接必须以 http:// 或 https:// 开头")
    private String sourceUrl;

    @Schema(description = "经验要求")
    @Size(max = 128, message = "经验要求最长 128 个字符")
    private String experienceRequired;

    @Schema(description = "学历要求")
    @Size(max = 128, message = "学历要求最长 128 个字符")
    private String educationRequired;

    @Schema(description = "JD 正文")
    @Size(max = 50000, message = "职位描述最长 50000 个字符")
    private String jobDescription;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;
}
