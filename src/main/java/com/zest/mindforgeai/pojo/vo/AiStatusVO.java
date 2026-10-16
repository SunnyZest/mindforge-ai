package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI 能力状态（首页状态徽章）。
 * <p>
 * ★ <b>刻意不暴露模型名</b>：这个接口只回答「AI 能不能用」。把内部选型
 * （具体用哪个模型）摊到用户端没有任何好处 —— 模型会换、会降级，写死在前端
 * 反而稳定；写活了，管理端一改配置首页就跟着变。
 *
 * @author crx
 * @since 2026-10-16
 */
@Schema(description = "AI 能力状态")
@Data
public class AiStatusVO {

    @Schema(description = "READY 就绪 / DEGRADED 配置不完整 / OFFLINE 服务离线")
    private String status;

    @Schema(description = "AI 模型密钥是否已配置（配合 status 排查用）")
    private Boolean llmConfigured;
}
