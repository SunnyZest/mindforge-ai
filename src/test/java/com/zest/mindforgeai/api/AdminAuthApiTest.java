package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
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
 * 管理端认证：{@code /api/admin/auth/**}。
 *
 * <p>此前只有 {@code login} 被各处测试顺带用到，{@code me} / {@code refresh} /
 * {@code logout} / {@code password} 四个端点一条用例都没有 ——
 * 而这四个恰好是「会话生命周期」的全部：谁能看到自己是谁、令牌怎么续、怎么作废、怎么改密。
 *
 * <p>★ 两条容易漏的语义：
 * <ol>
 *   <li>{@code refresh} 是<b>轮换</b>的：旧 refreshToken 立刻作废，
 *       而且旧令牌被重放时会判定为泄露、撤销该用户全部令牌；</li>
 *   <li>{@code me} 走的是 {@code /admin/**} 的 URL 规则，普通用户拿到的是 <b>403</b> 而不是 401 ——
 *       401 是「没带令牌」，403 是「带了但不是管理员」。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-18
 */
@DisplayName("管理端-认证")
class AdminAuthApiTest extends AbstractApiTest {

    private String adminToken;
    private long adminId;

    @BeforeEach
    void loginAdmin() {
        adminToken = loginAsAdmin();
        adminId = userId(ADMIN_NAME);
    }

    // ------------------------------------------------------------ me

    @Test
    @DisplayName("★ me：返回当前管理员的角色与权限码")
    void meReturnsCurrentAdmin() {
        ApiResult res = get("/admin/auth/me", adminToken).assertSuccess().assertCamelCase();

        assertThat(res.data().path("id").asText()).isEqualTo(String.valueOf(adminId));
        assertThat(res.data().path("username").asText()).isEqualTo(ADMIN_NAME);
        assertThat(texts(res.data().path("roles"))).contains("SUPER_ADMIN");
        assertThat(res.data().path("permissions").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("me：不带令牌 → 401（未登录）")
    void meWithoutTokenIsUnauthorized() {
        get("/admin/auth/me", null).assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("★ me：普通用户带令牌 → 403（带了令牌但不是管理员，和 401 不是一回事）")
    void meWithUserTokenIsForbidden() {
        get("/admin/auth/me", loginAsUser()).assertHttpStatus(403);
    }

    // ------------------------------------------------------------ refresh

    @Test
    @DisplayName("★ refresh：轮换令牌 —— 新的 refreshToken 与旧的不同")
    void refreshRotatesToken() {
        String refreshToken = loginRaw("/admin/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("refreshToken");

        ApiResult res = post("/admin/auth/refresh", null, Map.of("refreshToken", refreshToken))
                .assertSuccess().assertCamelCase();

        assertThat(res.str("accessToken")).isNotBlank();
        assertThat(res.str("refreshToken")).isNotBlank().isNotEqualTo(refreshToken);
        assertThat(res.data().path("expiresIn").asLong()).isPositive();
    }

    @Test
    @DisplayName("★ refresh：旧令牌重放 → 40103，且被判定为泄露")
    void replayedRefreshTokenIsRejected() {
        String refreshToken = loginRaw("/admin/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("refreshToken");

        post("/admin/auth/refresh", null, Map.of("refreshToken", refreshToken)).assertSuccess();

        // 同一张旧令牌再用一次：轮换后它已经作废
        post("/admin/auth/refresh", null, Map.of("refreshToken", refreshToken))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("refresh：乱编的令牌 → 40103")
    void bogusRefreshTokenIsRejected() {
        post("/admin/auth/refresh", null, Map.of("refreshToken", "not-a-real-token"))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());
    }

    // ------------------------------------------------------------ logout

    @Test
    @DisplayName("★ logout：撤销后同一张 refreshToken 再也换不到 accessToken")
    void logoutRevokesRefreshToken() {
        String refreshToken = loginRaw("/admin/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("refreshToken");

        post("/admin/auth/logout", null, Map.of("refreshToken", refreshToken)).assertSuccess();

        post("/admin/auth/refresh", null, Map.of("refreshToken", refreshToken))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("logout：登出不依赖 accessToken（公开接口，只认 body 里的 refreshToken）")
    void logoutDoesNotNeedAccessToken() {
        String refreshToken = loginRaw("/admin/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("refreshToken");

        // 不带 Authorization 头
        post("/admin/auth/logout", null, Map.of("refreshToken", refreshToken)).assertSuccess();
    }

    // ------------------------------------------------------------ 改密码

    @Test
    @DisplayName("★ 改密码：新密码立刻生效，旧密码失效")
    void changePasswordTakesEffect() {
        String token = adminRoleToken("auth_pwd");

        post("/admin/auth/password", token, Map.of(
                "oldPassword", TEST_PASSWORD,
                "newPassword", "new-pass-9876")).assertSuccess();

        loginRaw("/admin/auth/login", "auth_pwd", "new-pass-9876").assertSuccess();
        loginRaw("/admin/auth/login", "auth_pwd", TEST_PASSWORD)
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("改密码：原密码错 → 40001")
    void changePasswordRejectsWrongOldPassword() {
        String token = adminRoleToken("auth_pwd_bad");

        post("/admin/auth/password", token, Map.of(
                "oldPassword", "definitely-wrong",
                "newPassword", "new-pass-9876"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("改密码：新密码与原密码相同 → 40001（等于没改）")
    void changePasswordRejectsSamePassword() {
        String token = adminRoleToken("auth_pwd_same");

        post("/admin/auth/password", token, Map.of(
                "oldPassword", TEST_PASSWORD,
                "newPassword", TEST_PASSWORD))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("改密码：新密码过短 → 40001")
    void changePasswordValidatesLength() {
        String token = adminRoleToken("auth_pwd_short");

        post("/admin/auth/password", token, Map.of(
                "oldPassword", TEST_PASSWORD,
                "newPassword", "123"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("改密码：不带令牌 → 401")
    void changePasswordRequiresLogin() {
        post("/admin/auth/password", null, Map.of(
                "oldPassword", TEST_PASSWORD,
                "newPassword", "new-pass-9876"))
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 造一个 ADMIN 角色账号并登录。
     * <p>
     * ⚠️ 不能直接改 {@code t_admin} 的密码 —— 基类的 {@code ensureAccount}
     * 只在账号不存在时才创建，不会重置密码；改了会让本类其他用例（以及别的测试类）登录不上。
     */
    private String adminRoleToken(String username) {
        post("/admin/users", adminToken, Map.of(
                "username", username,
                "password", TEST_PASSWORD,
                "roleIds", List.of(roleId("ADMIN")))).assertSuccess();

        return loginRaw("/admin/auth/login", username, TEST_PASSWORD)
                .assertSuccess().str("accessToken");
    }

    private long roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code = ?", Long.class, code);
    }

    private List<String> texts(com.fasterxml.jackson.databind.JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }
}
