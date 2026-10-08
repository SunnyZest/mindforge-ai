package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改 AI 模型配置。
 * <p>
 * 字段都可空：<b>传 null 表示不修改</b>。
 *
 * <h3>关于 apiKey</h3>
 * 传 null = <b>保持原有 Key 不变</b>（不是清空）。这是刻意的：
 * 列表接口不返回明文，所以前端编辑表单里本来就没有原 Key，
 * 如果 null 被理解成「清空」，管理员改个备注就会把 Key 弄丢。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "修改 AI 模型配置")
@Data
public class AiModelUpdateDTO {

    @Schema(description = "配置显示名")
    @Size(max = 64, message = "配置名称最长 64 个字符")
    private String name;

    @Schema(description = "用途：CHAT / EMBEDDING / RERANK")
    @Pattern(regexp = "CHAT|EMBEDDING|RERANK", message = "用途只能是 CHAT / EMBEDDING / RERANK")
    private String scene;

    @Schema(description = "提供方")
    @Size(max = 64, message = "提供方最长 64 个字符")
    private String provider;

    @Schema(description = "模型名")
    @Size(max = 128, message = "模型名最长 128 个字符")
    private String modelName;

    @Schema(description = "API 地址")
    @Size(max = 255, message = "API 地址最长 255 个字符")
    private String baseUrl;

    @Schema(description = "API Key；传 null 表示保持原有 Key 不变")
    @Size(max = 512, message = "API Key 最长 512 个字符")
    private String apiKey;

    @Schema(description = "采样温度")
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

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String description;
}
