package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理端 AI 模型配置。
 *
 * <p>重点是「同一用途只有一个默认」这条不变式 —— 它现在是<b>双保险</b>：
 * 代码层 {@code clearOtherDefaults} 先清后设，数据库层靠函数唯一索引
 * {@code uk_ai_model_default} 兜底。这里两边都要验：
 * 接口层验证业务行为，jdbc 直插验证约束真的生效（否则约束形同虚设）。
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("管理端 - AI 模型配置")
class AdminAiModelApiTest extends AbstractApiTest {

    private static final String PATH = "/admin/ai/models";

    private String token;

    @BeforeEach
    void setUp() {
        token = loginAsAdmin();
    }

    @Test
    @DisplayName("新增配置：apiKey 只回显尾号，密文不出现在响应里")
    void apiKeyIsMasked() {
        String plainKey = fakeKey();

        ApiResult res = create("对话主模型", "CHAT", plainKey, true).assertSuccess();

        assertThat(res.data().path("apiKeyHint").asText())
                .isEqualTo("****" + plainKey.substring(plainKey.length() - 4));
        // 响应体里既不该有明文 Key，也不该有密文列
        assertThat(res.body().toString()).doesNotContain(plainKey);
        assertThat(res.body().toString()).doesNotContain("apiKeyCipher");
    }

    @Test
    @DisplayName("★ 同 scene 设第二个默认时，第一个的默认自动被让出来")
    void onlyOneDefaultPerScene() {
        long first = create("对话-A", "CHAT", fakeKey(), true)
                .assertSuccess().longId();
        long second = create("对话-B", "CHAT", fakeKey(), true)
                .assertSuccess().longId();

        assertThat(isDefault(first)).isFalse();
        assertThat(isDefault(second)).isTrue();
        assertThat(countDefaults("CHAT")).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 改成默认（PUT）也是先清后设，不会撞唯一索引")
    void updateToDefaultClearsOthers() {
        long a = create("对话-A", "CHAT", fakeKey(), true)
                .assertSuccess().longId();
        long b = create("对话-B", "CHAT", fakeKey(), false)
                .assertSuccess().longId();

        // 把 B 改成默认 —— 这一步如果写成「先设后清」，会直接 1062
        put(PATH + "/" + b, token, Map.of("isDefault", true)).assertSuccess();

        assertThat(isDefault(a)).isFalse();
        assertThat(isDefault(b)).isTrue();
        assertThat(countDefaults("CHAT")).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 scene 的默认互不影响")
    void defaultsAreScopedByScene() {
        long chat = create("对话", "CHAT", fakeKey(), true)
                .assertSuccess().longId();
        long embed = create("向量", "EMBEDDING", fakeKey(), true)
                .assertSuccess().longId();

        assertThat(isDefault(chat)).isTrue();
        assertThat(isDefault(embed)).isTrue();
        assertThat(countDefaults("CHAT")).isEqualTo(1);
        assertThat(countDefaults("EMBEDDING")).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 数据库层兜底：绕过接口直插第二个默认 → 被唯一索引拦住")
    void databaseRejectsSecondDefault() {
        create("对话-A", "CHAT", fakeKey(), true).assertSuccess();

        // 模拟「有人手工改库 / 脚本导入」—— 代码约束挡不住的场景
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sys_ai_model (id, name, scene, provider, model_name, base_url,"
                        + " api_key_cipher, api_key_hint, is_default, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'CHAT', 'dashscope', 'm', 'http://x', 'c', '****0002', 1, 1, NOW(), NOW())",
                System.currentTimeMillis(), "手工插的默认"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("★ 多个「非默认」不冲突（函数索引里它们是 NULL，不参与唯一性）")
    void multipleNonDefaultsAreAllowed() {
        create("对话-A", "CHAT", fakeKey(), false).assertSuccess();
        create("对话-B", "CHAT", fakeKey(), false).assertSuccess();
        create("对话-C", "CHAT", fakeKey(), false).assertSuccess();

        assertThat(countDefaults("CHAT")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_ai_model WHERE scene = 'CHAT'", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("禁用的默认不占坑：另一条可以同时是默认")
    void disabledDefaultDoesNotBlock() {
        long disabled = create("对话-停用", "CHAT", fakeKey(), true)
                .assertSuccess().longId();
        // 先停用，再把它标为非默认（或反之）—— 总之它不再占「启用中的默认」这个坑
        put(PATH + "/" + disabled, token, Map.of("status", 0)).assertSuccess();

        // 另一条应该能顺利成为默认
        create("对话-启用", "CHAT", fakeKey(), true).assertSuccess();

        assertThat(countDefaults("CHAT")).isEqualTo(1);
    }

    @Test
    @DisplayName("删除配置")
    void deleteWorks() {
        long id = create("待删", "CHAT", fakeKey(), false)
                .assertSuccess().longId();
        delete(PATH + "/" + id, token).assertSuccess();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_ai_model WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("参数校验：不支持的 scene → 40001")
    void invalidSceneIsRejected() {
        create("野场景", "NOT_A_SCENE", fakeKey(), false)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 造一个假的 API Key。
     * <p>
     * ⚠️ <b>必须运行时生成，不能写成字面量</b>：本机环境的密钥重写层会改写
     * 源码/命令里形如 {@code sk-xxx} 的字面量（连回显都会被改写），
     * 所以「期望尾号」和「实际发的 Key」必须来自同一个变量，否则断言必然对不上。
     */
    private String fakeKey() {
        return "sk-" + UUID.randomUUID().toString().replace("-", "");
    }

    private ApiResult create(String name, String scene, String apiKey, boolean asDefault) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("scene", scene);
        body.put("provider", "dashscope");
        body.put("modelName", "qwen-test");
        body.put("baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        body.put("apiKey", apiKey);
        body.put("isDefault", asDefault);
        return post(PATH, token, body);
    }

    private boolean isDefault(long id) {
        Integer flag = jdbc.queryForObject(
                "SELECT is_default FROM sys_ai_model WHERE id = ?", Integer.class, id);
        return flag != null && flag == 1;
    }

    /** 只数「启用中的默认」—— 函数索引的口径就是它 */
    private int countDefaults(String scene) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_ai_model WHERE scene = ? AND is_default = 1 AND status = 1",
                Integer.class, scene);
    }
}
