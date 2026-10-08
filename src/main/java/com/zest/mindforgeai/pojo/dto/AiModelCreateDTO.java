package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建 AI 模型配置，文档 §二十五。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "创建 AI 模型配置")
@Data
public class AiModelCreateDTO {

    @Schema(description = "配置显示名", example = "对话主模型")
    @NotBlank(message = "配置名称不能为空")
    @Size(max = 64, message = "配置名称最长 64 个字符")
    private String name;

    /**
     * 用途。
     * <p>
     * 用枚举而不是自由字符串 —— 场景值是代码里分支判断的依据
     * （对话用 CHAT、向量化用 EMBEDDING），写错了不会报错、只会静默不生效。
     */
    @Schema(description = "用途：CHAT / EMBEDDING / RERANK")
    @NotBlank(message = "用途不能为空")
    @Pattern(regexp = "CHAT|EMBEDDING|RERANK", message = "用途只能是 CHAT / EMBEDDING / RERANK")
    private String scene;

    @Schema(description = "提供方", example = "dashscope")
    @NotBlank(message = "提供方不能为空")
    @Size(max = 64, message = "提供方最长 64 个字符")
    private String provider;

    @Schema(description = "模型名", example = "deepseek-v4-flash")
    @NotBlank(message = "模型名不能为空")
    @Size(max = 128, message = "模型名最长 128 个字符")
    private String modelName;

    @Schema(description = "API 地址（OpenAI 兼容协议的 base_url）")
    @Size(max = 255, message = "API 地址最长 255 个字符")
    private String baseUrl;

    /**
     * API Key 明文。
     * <p>
     * 只在请求体里出现，落库前会被加密；<b>响应体和日志里都不会出现它</b>。
     */
    @Schema(description = "API Key（明文只在本次请求里出现，落库前加密）")
    @Size(max = 512, message = "API Key 最长 512 个字符")
    private String apiKey;

    @Schema(description = "采样温度，0 ~ 2")
    @DecimalMin(value = "0.00", message = "温度不能小于 0")
    @DecimalMax(value = "2.00", message = "温度不能大于 2")
    private BigDecimal temperature;

    @Schema(description = "单次最大输出 token")
    @Min(value = 1, message = "maxTokens 至少为 1")
    @Max(value = 1000000, message = "maxTokens 过大")
    private Integer maxTokens;

    @Schema(description = "调用超时（秒）")
    @Min(value = 1, message = "超时至少 1 秒")
    @Max(value = 3600, message = "超时最多 3600 秒")
    private Integer timeoutSeconds;

    @Schema(description = "是否设为该用途下的默认配置")
    private Boolean isDefault;

    @Schema(description = "1 启用 / 0 禁用；不传默认 1")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String description;
}
