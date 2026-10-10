package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.properties.InternalApiProperties;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部接口：把 AI 模型配置交给 Python。
 *
 * <p>⚠️ 这个接口是<b>整个项目里唯一会返回明文 API Key 的</b>，所以除了功能，
 * 还要守住「它只在 /internal 下、只认服务令牌、不泄漏密文」这几条。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("内部接口 - AI 模型配置")
class InternalAiModelApiTest extends AbstractApiTest {

    private static final String PATH = "/internal/ai-models";
    private static final String PLAIN_KEY = "sk-test-plain-key-abcdef123456";

    @Autowired
    private InternalApiProperties internalApiProperties;

    /** 带服务令牌请求内部接口。 */
    private ApiResult getInternal(String serviceToken) {
        HttpHeaders headers = new HttpHeaders();
        if (serviceToken != null) {
            headers.set("X-Service-Token", serviceToken);
        }
        ResponseEntity<JsonNode> response = rest.exchange(
                PATH, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        return new ApiResult(response.getStatusCode().value(), response.getBody());
    }

    private ApiResult createModel(String name, String scene, String modelName, int status) {
        return post("/admin/ai/models", loginAsAdmin(), Map.of(
                "name", name,
                "scene", scene,
                "provider", "dashscope",
                "modelName", modelName,
                "baseUrl", "https://example.invalid/v1",
                "apiKey", PLAIN_KEY,
                "status", status));
    }

    // ---------- 鉴权 ----------

    @Test
    @DisplayName("不带服务令牌 → 401（这个接口会回明文 Key，绝不能裸奔）")
    void requiresServiceToken() {
        getInternal(null).assertHttpStatus(401);
    }

    @Test
    @DisplayName("服务令牌不对 → 401")
    void rejectsWrongServiceToken() {
        getInternal("definitely-not-the-token").assertHttpStatus(401);
    }

    @Test
    @DisplayName("普通用户的 JWT 换不到这个接口（它不走 JWT）")
    void jwtIsNotAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAsUser());
        ResponseEntity<JsonNode> response = rest.exchange(
                PATH, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode().value())
                .as("JWT 不该能访问内部接口 —— 它只认 X-Service-Token")
                .isEqualTo(401);
    }

    // ---------- 下发内容 ----------

    @Test
    @DisplayName("一条都没配时返回空列表（Python 那边会整体回退环境变量）")
    void emptyWhenNothingConfigured() {
        ApiResult result = getInternal(internalApiProperties.getServiceToken()).assertSuccess();

        assertThat(result.data().isArray()).isTrue();
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("★ 启用的配置会下发，而且 apiKey 是【解密后的明文】")
    void returnsDecryptedApiKey() {
        createModel("测试对话模型", "CHAT", "qwen-test-model", 1).assertSuccess();

        ApiResult result = getInternal(internalApiProperties.getServiceToken()).assertSuccess();
        assertThat(result.data()).hasSize(1);

        JsonNode model = result.data().get(0);
        assertThat(model.path("scene").asText()).isEqualTo("CHAT");
        assertThat(model.path("modelName").asText()).isEqualTo("qwen-test-model");
        assertThat(model.path("apiKey").asText())
                .as("Python 要拿这个 Key 去调模型，所以必须是能直接用的明文")
                .isEqualTo(PLAIN_KEY);
        // 字段名是 camelCase（对齐 Python 侧的读取逻辑）
        assertThat(model.has("modelName")).isTrue();
        assertThat(model.has("baseUrl")).isTrue();
        assertThat(model.has("isDefault")).isTrue();
    }

    @Test
    @DisplayName("★ 响应里不出现密文本身")
    void neverLeaksCipher() {
        createModel("测试对话模型", "CHAT", "qwen-test-model", 1).assertSuccess();

        ApiResult result = getInternal(internalApiProperties.getServiceToken()).assertSuccess();

        assertThat(result.body().toString())
                .as("下发明文是为了让 Python 能用，但密文没有理由出现在任何响应里")
                .doesNotContain("apiKeyCipher")
                .doesNotContain("api_key_cipher");
    }

    @Test
    @DisplayName("禁用的配置不下发")
    void disabledIsNotDelivered() {
        createModel("禁用的模型", "CHAT", "disabled-model", 0).assertSuccess();

        ApiResult result = getInternal(internalApiProperties.getServiceToken()).assertSuccess();
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("启用和禁用混在一起时，只下发启用的")
    void onlyEnabledIsDelivered() {
        createModel("启用的", "CHAT", "enabled-model", 1).assertSuccess();
        createModel("禁用的", "EMBEDDING", "disabled-model", 0).assertSuccess();

        ApiResult result = getInternal(internalApiProperties.getServiceToken()).assertSuccess();

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).path("modelName").asText()).isEqualTo("enabled-model");
    }

    @Test
    @DisplayName("多个场景一起下发")
    void deliversMultipleScenes() {
        createModel("对话", "CHAT", "chat-model", 1).assertSuccess();
        createModel("向量", "EMBEDDING", "embedding-model", 1).assertSuccess();

        ApiResult result = getInternal(internalApiProperties.getServiceToken()).assertSuccess();

        assertThat(result.data()).hasSize(2);
        assertThat(result.data()).extracting(node -> node.path("scene").asText())
                .containsExactlyInAnyOrder("CHAT", "EMBEDDING");
    }

    @Test
    @DisplayName("没填 Key 的配置也能下发（本地模型不需要 Key），apiKey 为 null")
    void configWithoutApiKeyIsDeliveredWithNullKey() {
        post("/admin/ai/models", loginAsAdmin(), Map.of(
                "name", "本地模型", "scene", "CHAT", "provider", "local",
                "modelName", "local-model", "status", 1)).assertSuccess();

        ApiResult result = getInternal(internalApiProperties.getServiceToken()).assertSuccess();

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).path("apiKey").isNull())
                .as("没配 Key 就是 null，不是空串 —— Python 侧靠它判断该不该报「没有可用的 Key」")
                .isTrue();
    }

    @Test
    @DisplayName("管理端改配置后，下一次下发就是新的（没有缓存）")
    void reflectsAdminChangesImmediately() {
        long id = createModel("对话", "CHAT", "old-model", 1).assertSuccess().longId();
        assertThat(getInternal(internalApiProperties.getServiceToken())
                .assertSuccess().data().get(0).path("modelName").asText()).isEqualTo("old-model");

        put("/admin/ai/models/" + id, loginAsAdmin(), Map.of("modelName", "new-model")).assertSuccess();

        assertThat(getInternal(internalApiProperties.getServiceToken())
                .assertSuccess().data().get(0).path("modelName").asText())
                .as("内部接口不该缓存 —— Python 每 60 秒拉一次，拉到的必须是最新的")
                .isEqualTo("new-model");
    }
}
