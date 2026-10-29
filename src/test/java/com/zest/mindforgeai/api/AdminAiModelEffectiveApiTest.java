package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 管理端「AI 配置当前生效」（{@code GET /admin/ai/models/effective}）。
 * <p>
 * 这个接口的实质是<b>纯转发</b>，所以用例的重点不在「怎么算」，而在三件事：
 * <ol>
 *   <li><b>不重算</b>：Python 说什么就返回什么，Java 不能拿 {@code sys_ai_model} 自己判一遍
 *       （两套逻辑必然漂移）；</li>
 *   <li><b>不漏 Key</b>：响应里连 {@code apiKeyHint} 都不该有，只有 {@code hasKey}；</li>
 *   <li><b>失败不装成功</b>：Python 不可达时必须报错，不能返回空列表 ——
 *       空列表的意思是「没有任何生效配置」，与「查不到」是两件事。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-29
 */
@DisplayName("管理端 - AI 配置当前生效")
class AdminAiModelEffectiveApiTest extends AbstractApiTest {

    private static final String PATH = "/admin/ai/models/effective";

    private String token;

    @BeforeEach
    void setUp() {
        token = loginAsAdmin();
    }

    private static AiServiceClient.SceneConfig scene(String scene, String model,
                                                    String source, String baseUrl, boolean hasKey) {
        return new AiServiceClient.SceneConfig(scene, model, source, baseUrl, hasKey);
    }

    private void stub(AiServiceClient.EffectiveConfigData data) {
        when(aiServiceClient.effectiveConfig()).thenReturn(data);
    }

    @Test
    @DisplayName("三个用途原样透传，来源与「上次同步时间」都带上")
    void returnsSnapshotAsIs() {
        stub(new AiServiceClient.EffectiveConfigData(
                List.of(
                        scene("CHAT", "qwen3.8-flash", "java",
                                "https://dashscope.aliyuncs.com/compatible-mode/v1", true),
                        scene("EMBEDDING", "qwen3.7-text-embedding-flash", "java",
                                "https://dashscope.aliyuncs.com/compatible-mode/v1", true),
                        // RERANK 一条都没配：model 是空串，source 回退 env
                        scene("RERANK", "", "env",
                                "https://dashscope.aliyuncs.com/compatible-mode/v1", false)),
                "2026-10-29T09:40:00+08:00",
                null));

        ApiResult res = get(PATH, token).assertSuccess();

        assertThat(res.data().path("scenes").size()).isEqualTo(3);
        assertThat(res.data().path("scenes").get(0).path("scene").asText()).isEqualTo("CHAT");
        assertThat(res.data().path("scenes").get(0).path("model").asText()).isEqualTo("qwen3.8-flash");
        assertThat(res.data().path("scenes").get(0).path("source").asText()).isEqualTo("java");
        assertThat(res.data().path("scenes").get(0).path("hasKey").asBoolean()).isTrue();

        // ★ 没配的用途也要出现 —— 否则管理端看不出「这个用途在用默认值」
        assertThat(res.data().path("scenes").get(2).path("scene").asText()).isEqualTo("RERANK");
        assertThat(res.data().path("scenes").get(2).path("model").asText()).isEmpty();
        assertThat(res.data().path("scenes").get(2).path("source").asText()).isEqualTo("env");

        // refreshedAt 当字符串透传，时区偏移不能被吃掉
        assertThat(res.data().path("refreshedAt").asText()).isEqualTo("2026-10-29T09:40:00+08:00");
        assertThat(res.data().path("lastError").isNull()).isTrue();
    }

    @Test
    @DisplayName("★ 响应里只有 hasKey，没有任何 Key（连掩码都没有）")
    void neverExposesApiKey() {
        stub(new AiServiceClient.EffectiveConfigData(
                List.of(scene("CHAT", "qwen3.8-flash", "java", "https://x.invalid/v1", true)),
                "2026-10-29T09:40:00+08:00",
                null));

        String body = get(PATH, token).assertSuccess().body().toString();

        assertThat(body).contains("hasKey");
        assertThat(body).doesNotContain("apiKey");
        assertThat(body).doesNotContain("apiKeyHint");
        assertThat(body).doesNotContain("apiKeyCipher");
    }

    @Test
    @DisplayName("上次同步失败时 lastError 如实带出来（前端要能提示「改了没生效」）")
    void carriesLastError() {
        stub(new AiServiceClient.EffectiveConfigData(
                List.of(scene("CHAT", "qwen3.8-flash", "java", "https://x.invalid/v1", true)),
                "2026-10-29T09:40:00+08:00",
                "Connection refused"));

        ApiResult res = get(PATH, token).assertSuccess();

        assertThat(res.data().path("lastError").asText()).isEqualTo("Connection refused");
    }

    @Test
    @DisplayName("★ Python 不可达 → 50201，不是空列表")
    void pythonDownIsAnErrorNotEmptyList() {
        when(aiServiceClient.effectiveConfig())
                .thenThrow(new BizException(ErrorCode.AI_SERVICE_TIMEOUT, "AI 服务暂时不可用"));

        ApiResult res = get(PATH, token);

        // 空列表会被前端理解成「确实没有生效配置」，管理员就会去配一个本来就有的模型
        res.assertCode(ErrorCode.AI_SERVICE_ERROR.getCode());
        assertThat(res.message()).contains("AI 服务不可用");
        // ⚠️ 不能写 assertThat(res.data()).isNull() —— 响应里是 JSON null，
        //    Jackson 给的是 NullNode（一个对象），断言会报「expected: null but was: null」
        assertThat(res.isDataNull()).isTrue();
    }

    @Test
    @DisplayName("普通用户访问 → 403（这条要 admin:ai:manage）")
    void userCannotReadIt() {
        ApiResult res = get(PATH, loginAsUser());

        assertThat(res.code()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }
}
