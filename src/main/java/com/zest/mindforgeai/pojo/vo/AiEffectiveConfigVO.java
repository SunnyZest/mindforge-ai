package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管理端「AI 配置当前生效」视图。
 * <p>
 * ★ <b>这不是 {@code sys_ai_model} 的投影</b>，而是 Python 内存缓存里
 * <b>真正在用</b>的那份配置。两者经常不一致，而「不一致」正是这个接口要暴露的东西：
 * <ul>
 *   <li>某个用途有多条配置但都没勾默认 → 实际生效的是 <b>id 最大</b>的那条；</li>
 *   <li>某个用途一条都没配 → 实际走 <b>.env</b>，数据库里根本没有这条记录。</li>
 * </ul>
 * 只列表格（{@link AiModelVO}）永远答不出「现在到底在用什么」。
 * <p>
 * ⚠️ <b>不含 api_key，连掩码都不含</b> —— 只有 {@code hasKey}。
 * 这个接口要回答的是「通没通」，不是「Key 是什么」。
 * <p>
 * ⚠️ <b>不在 Java 侧重算</b>：生效规则（缓存优先、回退 .env）和 .env 的模型名
 * 都只有 Python 知道。重算一遍必然漂移，且这种漂移最难查 —— 两边都觉得自己对。
 *
 * @author crx
 * @since 2026-10-29
 */
@Schema(description = "AI 配置当前生效情况（转发 Python 内存快照）")
@Data
public class AiEffectiveConfigVO {

    @Schema(description = "每个用途一条，没配置的也会出现（source=env）")
    private List<SceneVO> scenes;

    @Schema(description = "上一次成功同步配置的时间（ISO 带时区，如 2026-10-29T09:40:00+08:00）；"
            + "null = 从没成功同步过")
    private String refreshedAt;

    @Schema(description = "上一次同步失败的原因；成功时为 null")
    private String lastError;

    /**
     * 单个用途的生效配置。
     */
    @Schema(description = "单个用途的生效配置")
    @Data
    public static class SceneVO {

        @Schema(description = "用途：CHAT / EMBEDDING / RERANK")
        private String scene;

        @Schema(description = "生效的模型名；空串表示未配置（RERANK 没有 .env 兜底）")
        private String model;

        @Schema(description = "来源：java = 来自管理端配置；env = 回退环境变量")
        private String source;

        @Schema(description = "生效的 base url")
        private String baseUrl;

        @Schema(description = "是否有可用的 API Key（⚠️ 永远不返回 Key 本身）")
        private Boolean hasKey;
    }
}
