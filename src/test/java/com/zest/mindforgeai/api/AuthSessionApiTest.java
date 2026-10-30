package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录设备（会话）管理：列表 / 踢单个 / 退出其他设备。
 * <p>
 * 核心要守住的是三件事：
 * <ol>
 *   <li><b>轮转不新增设备</b> —— refresh 一次就多一条会话的话，这个功能直接废掉</li>
 *   <li><b>踢人不伤己</b> —— 被踢的设备下次刷新不能把这个账号的全部会话带下线</li>
 *   <li><b>不能踢别人的</b> —— 只按 sessionId 更新就是水平越权</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-30
 */
@DisplayName("登录设备管理")
class AuthSessionApiTest extends AbstractApiTest {

    private List<JsonNode> sessions(String token) {
        ApiResult result = get("/app/auth/sessions", token).assertSuccess().assertCamelCase();
        assertThat(result.data().isArray()).as("会话列表应该是数组").isTrue();
        return iterableToList(result.data());
    }

    private List<JsonNode> iterableToList(JsonNode array) {
        java.util.List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private String sessionIdOf(JsonNode session) {
        return session.get("sessionId").asText();
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("登录后能在设备列表里看到自己，并标记为 current")
    void loginCreatesOneSession() {
        ApiResult login = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        String token = login.str("accessToken");

        List<JsonNode> list = sessions(token);
        assertThat(list).hasSize(1);

        JsonNode only = list.get(0);
        assertThat(only.get("current").asBoolean()).isTrue();
        assertThat(only.get("endType").asText()).isEqualTo("APP");
        assertThat(only.get("deviceName").asText()).isNotBlank();
        assertThat(only.get("sessionId").asText()).isNotBlank();
        // 时间字段不能是 null —— 前端要直接渲染
        assertThat(only.hasNonNull("createdAt")).isTrue();
        assertThat(only.hasNonNull("lastActiveAt")).isTrue();
    }

    @Test
    @DisplayName("同端登录两次 = 两台设备，只有一台是 current")
    void twoLoginsMakeTwoSessions() {
        String tokenA = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        String tokenB = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        List<JsonNode> listA = sessions(tokenA);
        assertThat(listA).hasSize(2);
        assertThat(listA).filteredOn(n -> n.get("current").asBoolean()).hasSize(1);

        // B 看到的列表里，current 应该是 B 自己那条
        List<JsonNode> listB = sessions(tokenB);
        assertThat(listB).hasSize(2);
        assertThat(sessionIdOf(listB.stream()
                .filter(n -> n.get("current").asBoolean()).findFirst().orElseThrow()))
                .isNotEqualTo(sessionIdOf(listA.stream()
                        .filter(n -> n.get("current").asBoolean()).findFirst().orElseThrow()));
    }

    @Test
    @DisplayName("刷新令牌不会新增设备（轮转必须继承同一个会话）")
    void refreshDoesNotCreateNewSession() {
        ApiResult login = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        String accessToken = login.str("accessToken");
        String refreshToken = login.str("refreshToken");

        assertThat(sessions(accessToken)).hasSize(1);

        // 连续刷新两次 —— 如果会话没继承，这里会变成 3 条
        for (int i = 0; i < 2; i++) {
            ApiResult refreshed = post("/app/auth/refresh", null,
                    Map.of("refreshToken", refreshToken)).assertSuccess();
            accessToken = refreshed.str("accessToken");
            refreshToken = refreshed.str("refreshToken");
        }

        assertThat(sessions(accessToken))
                .as("刷新后会话数必须还是 1 —— 多了说明轮转没继承 sessionId")
                .hasSize(1);
    }

    // ---------- 踢人 ----------

    @Test
    @DisplayName("踢掉另一台设备：它从列表消失，且它的 refreshToken 失效")
    void kickOtherDevice() {
        ApiResult loginA = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        ApiResult loginB = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        String tokenA = loginA.str("accessToken");
        String refreshB = loginB.str("refreshToken");

        String sessionB = sessionIdOf(sessions(tokenA).stream()
                .filter(n -> !n.get("current").asBoolean()).findFirst().orElseThrow());

        delete("/app/auth/sessions/" + sessionB, tokenA).assertSuccess();

        assertThat(sessions(tokenA)).hasSize(1);

        // B 的 refreshToken 应该失效
        post("/app/auth/refresh", null, Map.of("refreshToken", refreshB))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("踢人不伤己：被踢设备的刷新不会把本机也带下线")
    void kickDoesNotCascadeToCurrentSession() {
        // 这是「按泄露处理」和「主动作废」没分开时最容易踩的坑：
        // B 被踢后客户端不知道，下次刷新拿着已作废的 token 来，
        // 如果服务端还按「重放 = 泄露」处理，就会 revokeAll，把 A 一起干掉。
        ApiResult loginA = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        ApiResult loginB = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        String tokenA = loginA.str("accessToken");
        String refreshB = loginB.str("refreshToken");

        String sessionB = sessionIdOf(sessions(tokenA).stream()
                .filter(n -> !n.get("current").asBoolean()).findFirst().orElseThrow());
        delete("/app/auth/sessions/" + sessionB, tokenA).assertSuccess();

        // B 用旧 token 刷新 → 被拒
        post("/app/auth/refresh", null, Map.of("refreshToken", refreshB))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());

        // A 必须还活着
        assertThat(sessions(tokenA))
                .as("踢掉 B 之后 A 的会话不能跟着消失")
                .hasSize(1);
        get("/app/auth/me", tokenA).assertSuccess();
    }

    @Test
    @DisplayName("退出其他设备：只剩当前这一条，其他都失效")
    void revokeOtherSessions() {
        ApiResult loginA = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        ApiResult loginB = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        ApiResult loginC = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD).assertSuccess();
        String tokenA = loginA.str("accessToken");

        assertThat(sessions(tokenA)).hasSize(3);

        post("/app/auth/sessions/revoke-others", tokenA, null).assertSuccess();

        assertThat(sessions(tokenA)).hasSize(1);

        post("/app/auth/refresh", null, Map.of("refreshToken", loginB.str("refreshToken")))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());
        post("/app/auth/refresh", null, Map.of("refreshToken", loginC.str("refreshToken")))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());

        // 当前这条还能用
        get("/app/auth/me", tokenA).assertSuccess();
    }

    @Test
    @DisplayName("踢别人的会话：40402（不返回 403，避免泄漏「这个 id 存在」）")
    void cannotKickOthersSession() {
        String tokenA = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        String victimSession = sessionIdOf(sessions(tokenA).get(0));

        // 换一个账号，拿 A 的 sessionId 去踢
        createAccount("t_intruder");
        String intruderToken = loginRaw("/app/auth/login", "t_intruder", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        delete("/app/auth/sessions/" + victimSession, intruderToken)
                .assertCode(ErrorCode.RESOURCE_NOT_FOUND.getCode());

        // A 的会话必须还在
        assertThat(sessions(tokenA)).hasSize(1);
    }

    @Test
    @DisplayName("踢一个不存在的会话：40402")
    void kickUnknownSession() {
        String token = loginRaw("/app/auth/login", USER_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        delete("/app/auth/sessions/999000000000000001", token)
                .assertCode(ErrorCode.RESOURCE_NOT_FOUND.getCode());
    }

    // ---------- 两端隔离 ----------

    @Test
    @DisplayName("用户端与管理端的会话互不可见")
    void appAndAdminSessionsAreIsolated() {
        // t_admin 是 SUPER_ADMIN，两个入口都能登
        String appToken = loginRaw("/app/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        String adminToken = loginRaw("/admin/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        List<JsonNode> appSessions = sessions(appToken);
        assertThat(appSessions).hasSize(1);
        assertThat(appSessions.get(0).get("endType").asText()).isEqualTo("APP");

        ApiResult adminList = get("/admin/auth/sessions", adminToken).assertSuccess();
        assertThat(adminList.data()).hasSize(1);
        assertThat(adminList.data().get(0).get("endType").asText()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("退出其他设备只作用于当前端，不碰另一端")
    void revokeOthersDoesNotTouchOtherEnd() {
        String appToken = loginRaw("/app/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        String adminToken = loginRaw("/admin/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        // 再登一次管理端，凑出两条后台会话
        String adminToken2 = loginRaw("/admin/auth/login", ADMIN_NAME, TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        post("/app/auth/sessions/revoke-others", appToken, null).assertSuccess();

        // 用户端本来就 1 条，不受影响
        assertThat(sessions(appToken)).hasSize(1);
        // 管理端两条都还在
        assertThat(get("/admin/auth/sessions", adminToken).assertSuccess().data()).hasSize(2);
        get("/admin/auth/me", adminToken2).assertSuccess();
    }

    // ---------- 鉴权 ----------

    @Test
    @DisplayName("不带 token 查设备列表：401")
    void listRequiresAuth() {
        get("/app/auth/sessions", null).assertHttpStatus(401);
    }
}
