package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端：提示词管理（CRUD + 发布 + 回滚 + 版本历史）。
 *
 * <p>核心是守住「版本管理能成立」的那条前提：<b>已发布的不能原地改</b>。
 * 允许改的话，回滚回去的「旧版」其实是被改过的内容，历史就不可信了。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("管理端 - Prompt 管理")
class AdminPromptApiTest extends AbstractApiTest {

    private static final String PATH = "/admin/prompts";
    private static final String SCENE = "RAG_CHAT";

    private String adminToken;

    @BeforeEach
    void loginAdmin() {
        // ⚠️ 必须在每个用例前登录 —— 有些用例不经过 create()，
        //    靠它懒初始化的话那些用例会拿着 null token 去请求，得到 401 而不是预期的错误码。
        adminToken = loginAsAdmin();
    }

    private Map<String, Object> promptBody(String name, String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("scene", SCENE);
        body.put("name", name);
        body.put("content", content);
        return body;
    }

    private long create(String name, String content) {
        return post(PATH, adminToken, promptBody(name, content)).assertSuccess().longId();
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM sys_prompt WHERE id = ?", String.class, id);
    }

    private String contentOf(long id) {
        return jdbc.queryForObject("SELECT content FROM sys_prompt WHERE id = ?", String.class, id);
    }

    private int versionOf(long id) {
        return jdbc.queryForObject("SELECT version FROM sys_prompt WHERE id = ?", Integer.class, id);
    }

    private ApiResult groups(String query) {
        return get(PATH + (query == null ? "" : "?" + query), adminToken);
    }

    // ---------- 鉴权 ----------

    @Test
    @DisplayName("不带 token → 401")
    void requiresAuth() {
        get(PATH, null).assertHttpStatus(401);
    }

    @Test
    @DisplayName("普通用户 → 403（改提示词会立刻影响所有用户的 AI 输出）")
    void normalUserIsForbidden() {
        String token = loginAsUser();
        get(PATH, token).assertHttpStatus(403);
        post(PATH, token, promptBody("x", "y")).assertHttpStatus(403);
        post(PATH + "/1/publish", token, null).assertHttpStatus(403);
    }

    // ---------- 创建 ----------

    @Test
    @DisplayName("创建后是 DRAFT，版本号从 1 开始")
    void createStartsAsDraft() {
        long id = create("默认", "你是助手 {context}");

        assertThat(statusOf(id)).isEqualTo("DRAFT");
        assertThat(versionOf(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 同一个 (scene, name) 再建 → 是给这个变体加新版本，不是新建变体")
    void createSameVariantBumpsVersion() {
        long v1 = create("默认", "第一版 {context}");
        long v2 = create("默认", "第二版 {context}");

        assertThat(versionOf(v1)).isEqualTo(1);
        assertThat(versionOf(v2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_prompt", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("不同 name → 是不同的变体，版本号各自从 1 开始")
    void differentNameIsANewVariant() {
        long a = create("默认", "A {context}");
        long b = create("严谨", "B {context}");

        assertThat(versionOf(a)).isEqualTo(1);
        assertThat(versionOf(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 场景统一转大写 —— 存小写的话 Python 按大写找永远找不到")
    void sceneIsNormalizedToUpper() {
        long id = post(PATH, loginAsAdmin(), Map.of(
                "scene", "rag_chat", "name", "默认", "content", "x {context}"))
                .assertSuccess().longId();

        assertThat(jdbc.queryForObject("SELECT scene FROM sys_prompt WHERE id = ?", String.class, id))
                .isEqualTo("RAG_CHAT");
    }

    @Test
    @DisplayName("★ 标记「允许用户切换」但没填选项名 → 40001（用户看到的就是它）")
    void selectableWithoutLabelIsRejected() {
        Map<String, Object> body = promptBody("默认", "x {context}");
        body.put("userSelectable", true);

        post(PATH, loginAsAdmin(), body).assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("scene / name / content 为空 → 40001")
    void requiredFieldsAreValidated() {
        assertThat(post(PATH, loginAsAdmin(), Map.of("name", "x", "content", "y"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode()).isSuccess()).isFalse();
        assertThat(post(PATH, loginAsAdmin(), Map.of("scene", SCENE, "content", "y"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode()).isSuccess()).isFalse();
        assertThat(post(PATH, loginAsAdmin(), Map.of("scene", SCENE, "name", "x"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode()).isSuccess()).isFalse();
    }

    // ---------- 改草稿 ----------

    @Test
    @DisplayName("改草稿：成功")
    void updateDraft() {
        long id = create("默认", "旧内容 {context}");

        put(PATH + "/" + id, adminToken, Map.of("content", "新内容 {context}")).assertSuccess();

        assertThat(contentOf(id)).isEqualTo("新内容 {context}");
        assertThat(statusOf(id)).as("改草稿不该改变状态").isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("★ 改已发布的版本 → 40902（要改就新建版本）")
    void updatePublishedIsRejected() {
        long id = create("默认", "内容 {context}");
        post(PATH + "/" + id + "/publish", adminToken, null).assertSuccess();

        put(PATH + "/" + id, adminToken, Map.of("content", "偷偷改掉 {context}"))
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());

        assertThat(contentOf(id))
                .as("拒绝之后内容必须还是原来的 —— 否则「已发布不能改」这条就形同虚设")
                .isEqualTo("内容 {context}");
    }

    // ---------- 发布 ----------

    @Test
    @DisplayName("发布：DRAFT → PUBLISHED")
    void publishMovesToPublished() {
        long id = create("默认", "内容 {context}");

        post(PATH + "/" + id + "/publish", adminToken, null).assertSuccess();

        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("★ 发布新版本 → 同一变体下原来那版自动归档（一个变体同时只有一版生效）")
    void publishArchivesThePreviousOne() {
        long v1 = create("默认", "第一版 {context}");
        post(PATH + "/" + v1 + "/publish", adminToken, null).assertSuccess();

        long v2 = create("默认", "第二版 {context}");
        post(PATH + "/" + v2 + "/publish", adminToken, null).assertSuccess();

        assertThat(statusOf(v2)).isEqualTo("PUBLISHED");
        assertThat(statusOf(v1)).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("发布不会影响【别的变体】—— 严谨和简洁是两个独立的东西")
    void publishDoesNotTouchOtherVariants() {
        long a = create("默认", "A {context}");
        long b = create("严谨", "B {context}");
        post(PATH + "/" + a + "/publish", adminToken, null).assertSuccess();
        post(PATH + "/" + b + "/publish", adminToken, null).assertSuccess();

        assertThat(statusOf(a)).isEqualTo("PUBLISHED");
        assertThat(statusOf(b)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("重复发布同一版 → 40902")
    void publishTwiceIsRejected() {
        long id = create("默认", "内容 {context}");
        post(PATH + "/" + id + "/publish", adminToken, null).assertSuccess();

        post(PATH + "/" + id + "/publish", adminToken, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    @Test
    @DisplayName("★ 标了 isDefault 的版本发布时，同场景其他默认被清掉（一个场景只有一个默认）")
    void publishDefaultClearsOthers() {
        long a = create("默认", "A {context}");
        Map<String, Object> bodyA = promptBody("默认", "A {context}");
        bodyA.put("isDefault", true);
        long a2 = post(PATH, adminToken, bodyA).assertSuccess().longId();
        post(PATH + "/" + a2 + "/publish", adminToken, null).assertSuccess();

        // 另起一个变体也标默认
        Map<String, Object> bodyB = promptBody("严谨", "B {context}");
        bodyB.put("isDefault", true);
        long b = post(PATH, adminToken, bodyB).assertSuccess().longId();
        post(PATH + "/" + b + "/publish", adminToken, null).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_prompt WHERE scene = ? AND status = 'PUBLISHED' AND is_default = 1",
                Integer.class, SCENE))
                .as("同一个场景下只能有一个默认").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM sys_prompt WHERE is_default = 1", Long.class))
                .isEqualTo(b);
        assertThat(a).isNotNull();
    }

    // ---------- 回滚 ----------

    @Test
    @DisplayName("★ 回滚：把已归档的版本重新设为生效版本，当前那版归档")
    void rollbackRestoresArchivedVersion() {
        long v1 = create("默认", "第一版 {context}");
        post(PATH + "/" + v1 + "/publish", adminToken, null).assertSuccess();
        long v2 = create("默认", "第二版 {context}");
        post(PATH + "/" + v2 + "/publish", adminToken, null).assertSuccess();

        post(PATH + "/" + v1 + "/rollback", adminToken, null).assertSuccess();

        assertThat(statusOf(v1)).isEqualTo("PUBLISHED");
        assertThat(statusOf(v2)).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("回滚一个草稿 → 40902（草稿请用「发布」）")
    void rollbackDraftIsRejected() {
        long id = create("默认", "内容 {context}");

        post(PATH + "/" + id + "/rollback", adminToken, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    @Test
    @DisplayName("★ 回滚能真的把内容换回去（不只是改状态）")
    void rollbackBringsBackTheOldContent() {
        long v1 = create("默认", "第一版 {context}");
        post(PATH + "/" + v1 + "/publish", adminToken, null).assertSuccess();
        long v2 = create("默认", "第二版 {context}");
        post(PATH + "/" + v2 + "/publish", adminToken, null).assertSuccess();

        post(PATH + "/" + v1 + "/rollback", adminToken, null).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT content FROM sys_prompt WHERE scene = ? AND status = 'PUBLISHED'",
                String.class, SCENE))
                .as("生效的应该是第一版的内容").isEqualTo("第一版 {context}");
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("删除草稿：成功（这张表没有 deleted 列，物理删）")
    void deleteDraft() {
        long id = create("默认", "内容 {context}");

        delete(PATH + "/" + id, adminToken).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_prompt WHERE id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("★ 删除正在生效的版本 → 40902（先回滚到别的版本再删）")
    void deletePublishedIsRejected() {
        long id = create("默认", "内容 {context}");
        post(PATH + "/" + id + "/publish", adminToken, null).assertSuccess();

        delete(PATH + "/" + id, adminToken).assertCode(ErrorCode.STATUS_CONFLICT.getCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_prompt WHERE id = ?",
                Integer.class, id)).as("拒绝之后不能真删掉").isEqualTo(1);
    }

    @Test
    @DisplayName("不存在的 id → 40414")
    void missingPromptIsNotFound() {
        put(PATH + "/99999999", adminToken, Map.of("content", "x"))
                .assertHttpStatus(404).assertCode(ErrorCode.PROMPT_NOT_FOUND.getCode());
        post(PATH + "/99999999/publish", adminToken, null).assertHttpStatus(404);
        delete(PATH + "/99999999", adminToken).assertHttpStatus(404);
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("列表按变体分组，带全部版本历史（版本号倒序）")
    void listGroupsByVariant() {
        create("默认", "v1 {context}");
        create("默认", "v2 {context}");
        create("严谨", "严谨版 {context}");

        ApiResult result = groups(null).assertSuccess().assertCamelCase();
        assertThat(result.data()).hasSize(2);

        // ⚠️ 不要按索引取 —— 顺序是按 name 的排序规则来的，
        //    中文名之间谁在前取决于 collation，别在测试里假设。
        var group = findGroup(result.data(), "默认");
        assertThat(group).as("应该有一个叫「默认」的变体").isNotNull();
        assertThat(group.path("latestVersion").asInt()).isEqualTo(2);
        assertThat(group.path("versions")).hasSize(2);
        // 版本号倒序
        assertThat(group.path("versions").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(group.path("versions").get(1).path("version").asInt()).isEqualTo(1);
        assertThat(group.path("publishedId").isNull())
                .as("一版都没发布时 publishedId 应该是 null").isTrue();
    }

    private static com.fasterxml.jackson.databind.JsonNode findGroup(
            com.fasterxml.jackson.databind.JsonNode groups, String name) {
        for (com.fasterxml.jackson.databind.JsonNode group : groups) {
            if (name.equals(group.path("name").asText())) {
                return group;
            }
        }
        return null;
    }

    @Test
    @DisplayName("列表能按场景过滤")
    void listFiltersByScene() {
        create("默认", "内容 {context}");
        post(PATH, adminToken, Map.of("scene", "RESUME_PARSE", "name", "默认", "content", "x {r}"))
                .assertSuccess();

        assertThat(groups("scene=RAG_CHAT").assertSuccess().data()).hasSize(1);
        assertThat(groups("scene=resume_parse").assertSuccess().data())
                .as("场景过滤也要大小写不敏感").hasSize(1);
        assertThat(groups(null).assertSuccess().data()).hasSize(2);
    }

    @Test
    @DisplayName("发布后 publishedId 指向已发布的那一版")
    void publishedIdIsExposed() {
        long id = create("默认", "内容 {context}");
        post(PATH + "/" + id + "/publish", adminToken, null).assertSuccess();

        var group = findGroup(groups(null).assertSuccess().data(), "默认");
        assertThat(group).isNotNull();
        assertThat(group.path("publishedId").asText()).isEqualTo(String.valueOf(id));
    }

    // ---------- 审计 ----------

    @Test
    @DisplayName("★ 五个写操作全部记审计（改提示词会立刻影响所有用户）")
    void allWritesAreAudited() {
        long id = create("默认", "内容 {context}");
        put(PATH + "/" + id, adminToken, Map.of("content", "改过 {context}")).assertSuccess();
        post(PATH + "/" + id + "/publish", adminToken, null).assertSuccess();

        long v2 = create("默认", "第二版 {context}");
        post(PATH + "/" + v2 + "/publish", adminToken, null).assertSuccess();
        post(PATH + "/" + id + "/rollback", adminToken, null).assertSuccess();
        delete(PATH + "/" + v2, adminToken).assertSuccess();

        List<String> actions = jdbc.queryForList(
                "SELECT DISTINCT action FROM sys_audit_log WHERE resource_type = 'PROMPT'",
                String.class);
        assertThat(actions).containsExactlyInAnyOrder(
                "PROMPT_CREATE", "PROMPT_UPDATE", "PROMPT_PUBLISH",
                "PROMPT_ROLLBACK", "PROMPT_DELETE");
    }
}
