package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端权限字典：{@code GET /api/admin/permissions}。
 *
 * <p>权限是<b>内置字典</b>，只读 —— 这个控制器没有增删改端点，
 * 用例里除了验证内容，也顺带确认它确实只暴露了查询。
 *
 * <p>★ 权限码属于「谁能改权限」的元信息，只有 SUPER_ADMIN 能看：
 * ADMIN 角色虽然能管用户，但没有 {@code admin:permission:list}。
 *
 * @author crx
 * @since 2026-10-17
 */
@DisplayName("管理端-权限字典")
class AdminPermissionApiTest extends AbstractApiTest {

    private String adminToken;

    @BeforeEach
    void loginAdmin() {
        adminToken = loginAsAdmin();
    }

    @Test
    @DisplayName("★ 全量返回：含用户端与管理端权限码，按 code 升序")
    void listAllReturnsDictionary() {
        ApiResult res = get("/admin/permissions", adminToken).assertSuccess().assertCamelCase();

        JsonNode data = res.data();
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).as("权限字典应当有几十条").isGreaterThanOrEqualTo(50);

        List<String> codes = new ArrayList<>();
        data.forEach(node -> codes.add(node.path("code").asText()));

        assertThat(codes).contains("app:offer:read", "app:offer:write", "app:resume:match");
        assertThat(codes).contains("admin:user:assign-role", "admin:audit:list");
        assertThat(codes).as("权限码是唯一键，不该重复").doesNotHaveDuplicates();

        List<String> sorted = new ArrayList<>(codes);
        sorted.sort(String::compareTo);
        assertThat(codes).as("按 code 升序返回").isEqualTo(sorted);
    }

    @Test
    @DisplayName("每项字段完整：id / name / code / type 均非空")
    void eachItemHasRequiredFields() {
        JsonNode data = get("/admin/permissions", adminToken).assertSuccess().data();

        for (JsonNode node : data) {
            assertThat(node.path("id").asText()).isNotBlank();
            assertThat(node.path("name").asText()).isNotBlank();
            assertThat(node.path("code").asText()).isNotBlank();
            assertThat(node.path("type").asText()).isIn("MENU", "BUTTON", "API");
        }
    }

    @Test
    @DisplayName("★ ADMIN 有用户管理权限但没有 admin:permission:list → 403")
    void adminRoleCannotReadPermissions() {
        post("/admin/users", adminToken, Map.of(
                "username", "perm_admin",
                "password", TEST_PASSWORD,
                "roleIds", List.of(roleId("ADMIN")))).assertSuccess();

        String adminRoleToken = loginRaw("/admin/auth/login", "perm_admin", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        get("/admin/permissions", adminRoleToken).assertHttpStatus(403);
    }

    @Test
    @DisplayName("普通用户访问 → 403")
    void normalUserIsForbidden() {
        get("/admin/permissions", loginAsUser()).assertHttpStatus(403);
    }

    // ------------------------------------------------------------ 辅助

    private long roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code = ?", Long.class, code);
    }
}
