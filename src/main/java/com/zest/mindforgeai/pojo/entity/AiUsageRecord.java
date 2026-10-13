package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 使用量记录 —— 每次调用大模型写一条。
 *
 * <p>⚠️ 这张表<b>没有 deleted 列</b>，也没有 updated_at：用量记录是只增不改的事实流水，
 * 改了就不叫账了。
 *
 * <p>数据来源是 Python 服务（只有拿到模型响应的一方才看得到 token 数），
 * 见 {@code POST /internal/ai-usage}。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("ai_usage_record")
@Schema(description = "AiUsageRecord对象,AI 使用量记录")
public class AiUsageRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "触发用户 id；系统自动调用时为空")
    private Long userId;

    @Schema(description = "链路 id，可与日志文件、审计日志关联")
    private String requestId;

    @Schema(description = "业务场景：RAG_CHAT / RESUME_PARSE / JOB_PARSE / INTERVIEW 等")
    private String scene;

    @Schema(description = "模型提供方")
    private String provider;

    @Schema(description = "模型名")
    private String modelName;

    @Schema(description = "输入 token 数")
    private Integer promptTokens;

    @Schema(description = "输出 token 数")
    private Integer completionTokens;

    @Schema(description = "总 token 数")
    private Integer totalTokens;

    @Schema(description = "调用耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "调用结果：SUCCESS / FAILED")
    private String status;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
