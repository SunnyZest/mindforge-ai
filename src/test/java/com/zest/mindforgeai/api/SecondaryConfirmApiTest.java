package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高危操作二次确认。
 *
 * <p>后端对「删用户 / 批量删用户 / 删角色」强制要求凭据，缺了直接 {@code CONFIRM_REQUIRED}。
 * 这套机制此前<b>一条测试都没有</b>，而前端当时也没带凭据 ——
 * 也就是这三个操作在界面上其实是点了就报错的（2026-10-16 发现并修复）。
 * 这里把它锁住：以后谁再把凭据漏掉，测试会先炸。
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("高危操作二次确认")
class SecondaryConfirmApiTest extends AbstractApiTest {

    /** 与 {@link AbstractApiTest} 里造 admin 账号时用的密码一致 */
    private static final String ADMIN_PASSWORD = TEST_PASSWORD;

    private String adminToken;
    private Long victimId;

    @BeforeEach
    void setUp() {
        adminToken = loginAsAdmin();
        victimId = createAccount("confirm_victim");
    }

    // ------------------------------------------------------------ 拦截

    @Test
    @DisplayName("★ 不带任何凭据 → 40304 CONFIRM_REQUIRED（这正是前端之前的处境）")
    void withoutCredentialIsRejected() {
        delete("/admin/users/" + victimId, adminToken)
                .assertCode(ErrorCode.CONFIRM_REQUIRED.getCode());
        // 用户还在，说明确实被拦住了、没有「先删后报错」
        assertThat(accountExists(victimId)).isTrue();
    }

    @Test
    @DisplayName("批量删除同样被拦")
    void batchDeleteRequiresConfirm() {
        // ⚠️ 是 DELETE 且带 body（不是 POST）
        deleteWithBody("/admin/users/batch", Map.of("ids", List.of(String.valueOf(victimId))), null)
                .assertCode(ErrorCode.CONFIRM_REQUIRED.getCode());
        assertThat(accountExists(victimId)).isTrue();
    }

    @Test
    @DisplayName("删角色同样被拦（内置角色另有保护，这里先造一个普通角色）")
    void deleteRoleRequiresConfirm() {
        long roleId = createRole();
        delete("/admin/roles/" + roleId, adminToken)
                .assertCode(ErrorCode.CONFIRM_REQUIRED.getCode());
    }

    // ------------------------------------------------------------ 凭据

    @Test
    @DisplayName("★ 密码错 → 40305，拿不到凭证")
    void wrongPasswordCannotGetToken() {
        post("/admin/auth/confirm-token", adminToken, Map.of("password", "definitely-wrong-password"))
                .assertCode(ErrorCode.CONFIRM_FAILED.getCode());
    }

    @Test
    @DisplayName("★ 密码正确换到凭证，带上就能删掉")
    void correctTokenAllowsDelete() {
        String token = issueToken();

        deleteWithConfirmToken("/admin/users/" + victimId, token).assertSuccess();
        assertThat(accountExists(victimId)).isFalse();
    }

    @Test
    @DisplayName("★ 凭证是一次性的：用第二次 → 40305（防重放）")
    void tokenIsSingleUse() {
        String token = issueToken();

        deleteWithConfirmToken("/admin/users/" + victimId, token).assertSuccess();
        // 同一张凭证再用一次必须失败 —— 否则等于凭据可以无限复用
        Long another = createAccount("confirm_victim2");
        deleteWithConfirmToken("/admin/users/" + another, token)
                .assertCode(ErrorCode.CONFIRM_FAILED.getCode());
        assertThat(accountExists(another)).isTrue();
    }

    @Test
    @DisplayName("备选路径：直接带 X-Confirm-Password 也能过")
    void confirmPasswordHeaderWorks() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.set("X-Confirm-Password", ADMIN_PASSWORD);

        ResponseEntity<JsonNode> res = rest.exchange("/admin/users/" + victimId,
                HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class);

        assertThat(new ApiResult(res.getStatusCode().value(), res.getBody()).isSuccess()).isTrue();
        assertThat(accountExists(victimId)).isFalse();
    }

    @Test
    @DisplayName("凭证错（随便编一个）→ 40305")
    void bogusTokenIsRejected() {
        deleteWithConfirmToken("/admin/users/" + victimId, "not-a-real-confirm-token")
                .assertCode(ErrorCode.CONFIRM_FAILED.getCode());
        assertThat(accountExists(victimId)).isTrue();
    }

    // ------------------------------------------------------------ 辅助

    private String issueToken() {
        return post("/admin/auth/confirm-token", adminToken, Map.of("password", ADMIN_PASSWORD))
                .assertSuccess().data().path("confirmToken").asText();
    }

    private ApiResult deleteWithConfirmToken(String path, String confirmToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.set("X-Confirm-Token", confirmToken);
        ResponseEntity<JsonNode> res = rest.exchange(path, HttpMethod.DELETE,
                new HttpEntity<>(headers), JsonNode.class);
        return new ApiResult(res.getStatusCode().value(), res.getBody());
    }

    private ApiResult deleteWithBody(String path, Object body, String confirmToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (confirmToken != null) headers.set("X-Confirm-Token", confirmToken);
        ResponseEntity<JsonNode> res = rest.exchange(path, HttpMethod.DELETE,
                new HttpEntity<>(body, headers), JsonNode.class);
        return new ApiResult(res.getStatusCode().value(), res.getBody());
    }

    private boolean accountExists(Long id) {
        // ⚠️ 用户是逻辑删除：行还在，只把 deleted 置 1。漏掉 deleted = 0 会永远为 true
        return jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id = ? AND deleted = 0", Integer.class, id) > 0;
    }

    /** 造一个非内置的普通角色，用来验「删角色」这条链路 */
    private long createRole() {
        long id = java.util.concurrent.ThreadLocalRandom.current().nextLong(800_000L, 899_999L);
        // 真实列：id/name/code/status/description/created_at/updated_at/deleted/level
        jdbc.update("INSERT INTO sys_role (id, name, code, status, description, level, deleted, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, '二次确认测试用', 2, 0, NOW(), NOW())",
                id, "二次确认测试角色", "confirm_role_" + id);
        return id;
    }
}
