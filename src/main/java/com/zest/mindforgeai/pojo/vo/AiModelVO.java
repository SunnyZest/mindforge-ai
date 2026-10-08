package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.SysAiModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型配置（对外）。
 * <p>
 * ⚠️ <b>这个类里没有 apiKey 字段，这是设计而不是遗漏。</b>
 * 文档 §二十五 明确要求「列表接口不得返回明文」。
 * 与其依赖「记得把字段设为 null」这种约定，不如让它压根不存在 ——
 * 将来有人给实体加字段时，也不会顺着自动映射漏出去。
 * <p>
 * 对外只给 {@link #apiKeyHint}（形如 {@code ****abcd}）：
 * 管理员要确认「存的是哪一把」，不需要把 Key 抄出来。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "AI 模型配置")
@Data
public class AiModelVO {

    @Schema(description = "配置 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "配置显示名")
    private String name;

    @Schema(description = "用途：CHAT / EMBEDDING / RERANK")
    private String scene;

    @Schema(description = "提供方")
    private String provider;

    @Schema(description = "模型名")
    private String modelName;

    @Schema(description = "API 地址")
    private String baseUrl;

    @Schema(description = "API Key 尾部提示，如 ****abcd；未配置时为 null")
    private String apiKeyHint;

    @Schema(description = "是否已配置 API Key")
    private Boolean hasApiKey;

    @Schema(description = "采样温度")
    private BigDecimal temperature;

    @Schema(description = "单次最大输出 token")
    private Integer maxTokens;

    @Schema(description = "调用超时（秒）")
    private Integer timeoutSeconds;

    @Schema(description = "是否该用途下的默认配置")
    private Boolean isDefault;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "备注")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static AiModelVO of(SysAiModel model) {
        AiModelVO vo = new AiModelVO();
        vo.setId(model.getId() == null ? null : String.valueOf(model.getId()));
        vo.setName(model.getName());
        vo.setScene(model.getScene());
        vo.setProvider(model.getProvider());
        vo.setModelName(model.getModelName());
        vo.setBaseUrl(model.getBaseUrl());
        vo.setApiKeyHint(model.getApiKeyHint());
        vo.setHasApiKey(model.getApiKeyCipher() != null && !model.getApiKeyCipher().isBlank());
        vo.setTemperature(model.getTemperature());
        vo.setMaxTokens(model.getMaxTokens());
        vo.setTimeoutSeconds(model.getTimeoutSeconds());
        vo.setIsDefault(model.getIsDefault() != null && model.getIsDefault() == 1);
        vo.setStatus(model.getStatus());
        vo.setDescription(model.getDescription());
        vo.setCreatedAt(model.getCreatedAt());
        vo.setUpdatedAt(model.getUpdatedAt());
        return vo;
    }
}
