package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 薪酬估算入参。
 * <p>
 * ★ 刻意<b>不强制绑简历</b>：绑了就得先上传、先解析，把门槛抬得比收益还高。
 * 这里要的只是几个能决定薪资的量：城市、岗位、年限，其余都是可选微调项。
 *
 * @author crx
 * @since 2026-10-17
 */
@Schema(description = "薪酬估算请求")
@Data
public class SalaryEstimateDTO {

    @Schema(description = "城市", example = "杭州")
    @NotBlank(message = "城市不能为空")
    @Size(max = 64, message = "城市最长 64 个字符")
    private String city;

    @Schema(description = "岗位名称", example = "Java 后端开发")
    @NotBlank(message = "岗位不能为空")
    @Size(max = 128, message = "岗位最长 128 个字符")
    private String position;

    @Schema(description = "工作年限", example = "3")
    @NotNull(message = "工作年限不能为空")
    @Min(value = 0, message = "工作年限不能为负")
    @Max(value = 40, message = "工作年限最多 40 年")
    private Integer yearsOfExperience;

    @Schema(description = "学历：大专 / 本科 / 硕士 / 博士", example = "本科")
    @Size(max = 32, message = "学历最长 32 个字符")
    private String education;

    @Schema(description = "公司类型：大厂 / 中厂 / 创业 / 国企 / 外企", example = "大厂")
    @Size(max = 32, message = "公司类型最长 32 个字符")
    private String companyType;

    @Schema(description = "技术栈，最多 20 个")
    @Size(max = 20, message = "技术栈最多 20 个")
    private List<@Size(max = 32, message = "单个技术栈最长 32 个字符") String> skills;

    @Schema(description = "当前年包（元），用于给涨幅参考", example = "300000")
    @DecimalMin(value = "0", message = "当前年包不能为负")
    private BigDecimal currentAnnualSalary;
}
