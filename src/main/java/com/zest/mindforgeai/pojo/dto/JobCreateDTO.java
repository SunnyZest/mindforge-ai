package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建职位 JD，文档 §二十。
 * <p>
 * JD 是<b>文本</b>，不是文件 —— 用户通常是从招聘网站复制一段描述过来，
 * 所以这里直接收字符串，不做文件上传。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "创建职位 JD")
@Data
public class JobCreateDTO {

    @Schema(description = "职位名称", example = "Java 后端开发工程师")
    @NotBlank(message = "职位名称不能为空")
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
    private String sourceUrl;

    @Schema(description = "经验要求，如 3-5 年")
    @Size(max = 128, message = "经验要求最长 128 个字符")
    private String experienceRequired;

    @Schema(description = "学历要求，如 本科")
    @Size(max = 128, message = "学历要求最长 128 个字符")
    private String educationRequired;

    /**
     * JD 正文。
     * <p>
     * 数据库里叫 {@code raw_content}（原始文本），是 AI 解析的输入 ——
     * 所以这里收的必须是一手描述，不是用户自己的总结。
     */
    @Schema(description = "JD 正文（原始描述）")
    @NotBlank(message = "职位描述不能为空")
    private String jobDescription;
}
