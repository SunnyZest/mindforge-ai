package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 用户端应用状态：{@code GET /api/app/ai/status}。
 *
 * <p>这是首页那个「AI 可用性」徽章背后的接口，只有两个字段。
 *
 * <p>★ 最关键的一条：<b>AI 服务挂掉时这个接口也必须返回 200</b>。
 * 它是装饰性的状态探测，一旦往外抛异常，Python 一挂用户连首页都进不来 ——
 * 一个徽章把整个产品拖下水，完全不成比例。所以 OFFLINE 是一条正常响应，不是错误。
 *
 * @author crx
 * @since 2026-10-17
 */
@DisplayName("用户端-AI 状态")
class AppStatusApiTest extends AbstractApiTest {

    private static final String PATH = "/app/ai/status";

    @Test
    @DisplayName("★ 模型已配置 → READY，llmConfigured=true")
    void readyWhenConfigured() {
        when(aiServiceClient.status()).thenReturn(
                new AiServiceClient.StatusData(true, "DB"));

        ApiResult res = get(PATH, loginAsUser()).assertSuccess().assertCamelCase();

        assertThat(res.data().path("status").asText()).isEqualTo("READY");
        assertThat(res.data().path("llmConfigured").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ 服务在线但缺模型密钥 → DEGRADED（不报错，只是提示配置不全）")
    void degradedWhenNotConfigured() {
        when(aiServiceClient.status()).thenReturn(
                new AiServiceClient.StatusData(false, "ENV"));

        ApiResult res = get(PATH, loginAsUser()).assertSuccess();

        assertThat(res.data().path("status").asText()).isEqualTo("DEGRADED");
        assertThat(res.data().path("llmConfigured").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★ AI 服务连不上 → OFFLINE 且仍是 200（首页必须打得开）")
    void offlineWhenClientThrows() {
        when(aiServiceClient.status()).thenThrow(new RuntimeException("connection refused"));

        ApiResult res = get(PATH, loginAsUser()).assertSuccess();

        assertThat(res.data().path("status").asText()).isEqualTo("OFFLINE");
        assertThat(res.data().path("llmConfigured").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★ 只回两个字段：不暴露模型名 / 配置来源（内部选型不该摊到前端）")
    void doesNotLeakInternals() {
        when(aiServiceClient.status()).thenReturn(
                new AiServiceClient.StatusData(true, "DB"));

        ApiResult res = get(PATH, loginAsUser()).assertSuccess();

        assertThat(res.data().size()).isEqualTo(2);
        assertThat(res.data().has("configSource")).isFalse();
        assertThat(res.data().has("modelName")).isFalse();
        assertThat(res.data().has("model")).isFalse();
    }

    @Test
    @DisplayName("未登录访问 → 401（不是公开接口，只是没挂权限码）")
    void requiresLogin() {
        get(PATH, null).assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }
}
