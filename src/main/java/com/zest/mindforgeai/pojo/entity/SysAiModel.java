package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * AI 模型配置表
 * </p>
 * <p>
 * ⚠️ 这张表<b>没有 deleted 列</b>（和其他 {@code sys_*} 配置表一致），删除是物理删。
 * 对这张表来说这是好事：删掉配置的同时也删掉了里面存的 API Key 密文，
 * 不会留下一份没人管、但还能解开的凭据。
 *
 * @author crx
 * @since 2026-10-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_ai_model")
@Schema(description = "SysAiModel对象,AI 模型配置表")
public class SysAiModel implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

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

    /**
     * API Key 密文（AES-GCM）。
     * <p>
     * ⚠️ 这个字段<b>永远不能出现在响应体里，也不能进日志</b>。
     * 对外只暴露 {@link #apiKeyHint}。
     */
    @Schema(description = "API Key 密文（不对外）")
    private String apiKeyCipher;

    @Schema(description = "API Key 尾部提示，如 ****abcd")
    private String apiKeyHint;

    @Schema(description = "采样温度")
    private BigDecimal temperature;

    @Schema(description = "单次最大输出 token")
    private Integer maxTokens;

    @Schema(description = "调用超时（秒）")
    private Integer timeoutSeconds;

    @Schema(description = "同 scene 下的默认配置：1 是 / 0 否")
    private Integer isDefault;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "备注")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
