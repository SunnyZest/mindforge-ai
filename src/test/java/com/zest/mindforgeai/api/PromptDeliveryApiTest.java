package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.properties.InternalApiProperties;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词的<b>下发</b>：用户端能拿到什么、Python 能拿到什么。
 *
 * <p>这两条路的差别是这个功能的安全边界：
 * <ul>
 *   <li><b>用户端</b>只拿「可选的风格列表」，<b>不带正文</b>，而且只有管理员标了
 *       {@code user_selectable} 的才出现 —— 8 个 prompt 里有 7 个的输出格式是
 *       内部契约（resume_parse 必须吐特定 JSON 等），让用户切会直接让功能挂掉。</li>
 *   <li><b>内部接口</b>要拿到<b>所有已发布的</b>（含正文），因为 Python 得能按场景
 *       取默认那条来渲染 —— 包括不让用户切的那 7 个。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("提示词下发（用户端 + 内部）")
class PromptDeliveryApiTest extends AbstractApiTest {

    private static final String SCENE = "RAG_CHAT";
    private static final String OPTIONS_PATH = "/app/prompts";
    private static final String INTERNAL_PATH = "/internal/prompts";

    @Autowired
    private InternalApiProperties internalApiProperties;

    private String adminToken;

    @BeforeEach
    void prepareToken() {
        adminToken = loginAsAdmin();
    }

    /** 建一条并发布。 */
    private long publish(String name, String content, boolean selectable, String label,
                         boolean isDefault) {
        Map<String, Object> body = new HashMap<>();
        body.put("scene", SCENE);
        body.put("name", name);
        body.put("content", content);
        if (selectable) {
            body.put("userSelectable", true);
            body.put("label", label);
        }
        if (isDefault) {
            body.put("isDefault", true);
        }
        long id = post("/admin/prompts", adminToken, body).assertSuccess().longId();
        post("/admin/prompts/" + id + "/publish", adminToken, null).assertSuccess();
        return id;
    }

    private ApiResult options(String scene, String token) {
        return get(OPTIONS_PATH + "?scene=" + scene, token);
    }

    private ApiResult internal() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Token", internalApiProperties.getServiceToken());
        ResponseEntity<JsonNode> response = rest.exchange(
                INTERNAL_PATH, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        return new ApiResult(response.getStatusCode().value(), response.getBody());
    }

    // ---------- 用户端 ----------

    @Test
    @DisplayName("普通用户能拿到可选风格（app:prompt:read 已绑 USER）")
    void normalUserCanListOptions() {
        publish("默认", "默认风格 {context}", true, "默认", true);
        publish("严谨", "严谨风格 {context}", true, "严谨", false);

        ApiResult result = options(SCENE, loginAsUser()).assertSuccess().assertCamelCase();

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).path("label").asText())
                .as("默认的排最前").isEqualTo("默认");
        assertThat(result.data().get(0).path("isDefault").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ 用户端不返回提示词正文（原文里有防虚构规则和格式约定）")
    void optionsNeverExposeContent() {
        publish("默认", "这是不该泄漏的提示词正文 {context}", true, "默认", true);

        ApiResult result = options(SCENE, loginAsUser()).assertSuccess();

        assertThat(result.body().toString())
                .doesNotContain("不该泄漏")
                .doesNotContain("content");
        assertThat(result.data().get(0).has("content")).isFalse();
    }

    @Test
    @DisplayName("★ 没标「允许用户切换」的不出现在用户端（那 7 个内部契约 prompt）")
    void notSelectableIsHiddenFromUsers() {
        publish("默认", "可切的 {context}", true, "默认", true);
        publish("内部用", "不可切的 {context}", false, null, false);

        ApiResult result = options(SCENE, loginAsUser()).assertSuccess();

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).path("label").asText()).isEqualTo("默认");
    }

    @Test
    @DisplayName("★ 草稿不出现（管理员还没定稿的东西不该给用户看到）")
    void draftIsHidden() {
        post("/admin/prompts", adminToken, Map.of(
                "scene", SCENE, "name", "草稿", "content", "草稿 {context}",
                "userSelectable", true, "label", "草稿")).assertSuccess();

        assertThat(options(SCENE, loginAsUser()).assertSuccess().data()).isEmpty();
    }

    @Test
    @DisplayName("★ 已归档的版本不出现")
    void archivedIsHidden() {
        long v1 = publish("默认", "第一版 {context}", true, "默认", true);
        long v2 = publish("默认", "第二版 {context}", true, "默认", true);

        ApiResult result = options(SCENE, loginAsUser()).assertSuccess();

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).path("id").asText()).isEqualTo(String.valueOf(v2));
        assertThat(v1).isNotNull();
    }

    @Test
    @DisplayName("一条都没配时返回空数组（前端就不显示切换入口）")
    void emptyWhenNothingConfigured() {
        ApiResult result = options(SCENE, loginAsUser()).assertSuccess();

        assertThat(result.data().isArray()).isTrue();
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("别的场景的可选项不会混进来")
    void otherScenesAreNotMixedIn() {
        publish("默认", "对话风格 {context}", true, "默认", true);
        post("/admin/prompts", adminToken, Map.of(
                "scene", "RESUME_PARSE", "name", "默认", "content", "解析 {resume_text}",
                "userSelectable", true, "label", "解析默认")).assertSuccess();

        ApiResult result = options(SCENE, loginAsUser()).assertSuccess();

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).path("label").asText()).isEqualTo("默认");
    }

    @Test
    @DisplayName("不带 scene → 40001")
    void sceneIsRequired() {
        get(OPTIONS_PATH, loginAsUser()).assertCode(
                com.zest.mindforgeai.common.constant.ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("不带 token → 401")
    void optionsRequireAuth() {
        options(SCENE, null).assertHttpStatus(401);
    }

    // ---------- 内部接口 ----------

    @Test
    @DisplayName("不带服务令牌 → 401")
    void internalRequiresServiceToken() {
        get(INTERNAL_PATH, null).assertHttpStatus(401);
        get(INTERNAL_PATH, loginAsAdmin()).assertHttpStatus(401);
    }

    @Test
    @DisplayName("★ 内部接口拿得到【所有已发布的】（含不让用户切的那 7 个内部契约 prompt）")
    void internalGetsEverythingPublished() {
        publish("默认", "可切的 {context}", true, "默认", true);
        publish("内部用", "不可切的 {context}", false, null, false);

        ApiResult result = internal().assertSuccess().assertCamelCase();

        assertThat(result.data()).as("Python 要能按场景取默认那条来渲染，所以不可切的也要给")
                .hasSize(2);
        assertThat(result.data().get(0).path("content").asText())
                .as("内部接口必须带正文，否则 Python 渲染不了").isNotBlank();
        assertThat(result.data().get(0).path("isDefault").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ 内部接口不带草稿和已归档版本")
    void internalOnlyPublished() {
        publish("默认", "已发布 {context}", true, "默认", true);
        post("/admin/prompts", adminToken, Map.of(
                "scene", SCENE, "name", "草稿", "content", "草稿 {context}")).assertSuccess();

        ApiResult result = internal().assertSuccess();

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).path("content").asText()).isEqualTo("已发布 {context}");
    }

    @Test
    @DisplayName("一条都没配时返回空数组（Python 那边整体回退 prompts/*.txt）")
    void internalEmptyWhenNothingConfigured() {
        ApiResult result = internal().assertSuccess();

        assertThat(result.data().isArray()).isTrue();
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("内部接口带 userSelectable 标记 —— Python 靠它挡住「切到没开放的那条」")
    void internalExposesSelectableFlag() {
        publish("默认", "可切 {context}", true, "默认", true);
        publish("内部用", "不可切 {context}", false, null, false);

        ApiResult result = internal().assertSuccess();

        assertThat(result.data().get(0).path("userSelectable").asBoolean()).isTrue();
        assertThat(result.data().get(1).path("userSelectable").asBoolean()).isFalse();
    }
}
