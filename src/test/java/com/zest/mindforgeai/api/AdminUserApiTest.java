package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端用户管理：{@code /api/admin/users/**}。
 *
 * <p>这个控制器此前只有「删除」被 {@link SecondaryConfirmApiTest} 顺带覆盖到，
 * 列表 / 详情 / 创建 / 改资料 / 启停 / 分配角色 / 两个批量接口都没人管。
 *
 * <p>★ 本类重点盯三件容易回归的事：
 * <ol>
 *   <li><b>两层防护</b>：{@code /admin/**} 的 URL 规则（普通用户 403）
 *       与 {@code @PreAuthorize} 的权限码（ADMIN 缺码也 403）是两回事，各自都要有用例；</li>
 *   <li><b>垂直越权</b>：不能禁用 / 删除 / 改角色到「层级 >= 自己」的对象，
 *       也不能把 SUPER_ADMIN 角色分配出去 —— 这是提权的入口；</li>
 *   <li><b>禁用 = 立刻失效</b>：状态置 0 的同时必须撤销全部 refreshToken，
 *       否则被禁账号还能靠刷新令牌一直续命。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-17
 */
@DisplayName("管理端-用户管理")
class AdminUserApiTest extends AbstractApiTest {

    private String adminToken;

    @BeforeEach
    void loginAdmin() {
        adminToken = loginAsAdmin();
    }

    // ------------------------------------------------------------ 列表

    @Test
    @DisplayName("★ 列表：返回基础账号，角色一并带出，且不返回 permissions（避免 N+1）")
    void pageListsUsersWithRoles() {
        ApiResult res = get("/admin/users", adminToken).assertSuccess().assertCamelCase();

        assertThat(res.data().path("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(res.data().path("page").asLong()).isEqualTo(1);
        assertThat(res.data().path("pageSize").asLong()).isEqualTo(20);

        JsonNode admin = findRecord(res.data().path("records"), ADMIN_NAME);
        assertThat(admin).as("列表里应当有 " + ADMIN_NAME).isNotNull();
        assertThat(texts(admin.path("roles"))).contains("SUPER_ADMIN");
        assertThat(admin.has("permissions")).as("列表项不该带权限码").isFalse();
    }

    @Test
    @DisplayName("关键词过滤：命中用户名")
    void pageFiltersByKeyword() {
        ApiResult res = get("/admin/users?keyword=" + ADMIN_NAME, adminToken).assertSuccess();

        assertThat(res.data().path("total").asLong()).isEqualTo(1);
        assertThat(res.data().path("records").get(0).path("username").asText()).isEqualTo(ADMIN_NAME);
    }

    @Test
    @DisplayName("状态过滤：禁用后的账号只出现在 status=0 里")
    void pageFiltersByStatus() {
        long target = createAccount("au_status");
        put("/admin/users/" + target + "/status", adminToken, Map.of("status", 0)).assertSuccess();

        ApiResult disabled = get("/admin/users?status=0", adminToken).assertSuccess();
        assertThat(findRecord(disabled.data().path("records"), "au_status")).isNotNull();

        ApiResult enabled = get("/admin/users?status=1", adminToken).assertSuccess();
        assertThat(findRecord(enabled.data().path("records"), "au_status")).isNull();
    }

    @Test
    @DisplayName("★ 角色过滤：按 code 先查 userId 再 IN，不是把 code 拼进 SQL")
    void pageFiltersByRoleCode() {
        ApiResult superAdmins = get("/admin/users?role=SUPER_ADMIN", adminToken).assertSuccess();
        assertThat(superAdmins.data().path("total").asLong()).isEqualTo(1);
        assertThat(superAdmins.data().path("records").get(0).path("username").asText())
                .isEqualTo(ADMIN_NAME);

        ApiResult none = get("/admin/users?role=NO_SUCH_ROLE", adminToken).assertSuccess();
        assertThat(none.data().path("total").asLong()).isZero();
        assertThat(none.data().path("records")).isEmpty();
    }

    @Test
    @DisplayName("排序字段白名单：不在白名单里的 sortBy → 40001（绝不拼进 SQL）")
    void pageRejectsUnknownSortField() {
        get("/admin/users?sortBy=passwordHash", adminToken)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 详情

    @Test
    @DisplayName("★ 详情：带角色与权限码，但不泄漏状态 / 邮箱（VO 里本就没有）")
    void detailReturnsRolesAndPermissions() {
        long id = userId(USER_NAME);
        ApiResult res = get("/admin/users/" + id, adminToken).assertSuccess().assertCamelCase();

        assertThat(res.data().path("id").asText()).isEqualTo(String.valueOf(id));
        assertThat(res.data().path("username").asText()).isEqualTo(USER_NAME);
        assertThat(texts(res.data().path("roles"))).contains("USER");
        assertThat(res.data().path("permissions").size()).isGreaterThan(0);
        assertThat(res.data().has("status")).isFalse();
        assertThat(res.data().has("email")).isFalse();
    }

    @Test
    @DisplayName("详情：不存在的 id → 40401")
    void detailUnknownUserIsNotFound() {
        get("/admin/users/999999999", adminToken)
                .assertCode(ErrorCode.USER_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 创建

    @Test
    @DisplayName("★ 创建：不传角色时只绑默认 USER，密码 BCrypt 落库且能登录")
    void createUserBindsDefaultRole() {
        ApiResult res = post("/admin/users", adminToken, Map.of(
                "username", "au_created",
                "email", "au_created@test.local",
                "password", "init-pass-123",
                "nickname", "被创建的人")).assertSuccess().assertCamelCase();

        long id = res.data().path("id").asLong();
        assertThat(res.data().path("username").asText()).isEqualTo("au_created");
        assertThat(texts(res.data().path("roles"))).containsExactly("USER");

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM `user` WHERE id = ?", String.class, id);
        assertThat(hash).as("密码必须加密落库").isNotEqualTo("init-pass-123").startsWith("$2");

        // 新账号能直接用初始密码登录
        loginRaw("/app/auth/login", "au_created", "init-pass-123").assertSuccess();
    }

    @Test
    @DisplayName("创建：用户名重复 → 40901")
    void createUserRejectsDuplicateUsername() {
        post("/admin/users", adminToken, Map.of(
                "username", USER_NAME, "password", "whatever-123"))
                .assertCode(ErrorCode.USER_ALREADY_EXISTS.getCode());
    }

    @Test
    @DisplayName("创建：邮箱重复 → 40902")
    void createUserRejectsDuplicateEmail() {
        post("/admin/users", adminToken, Map.of(
                "username", "au_dup_email",
                "email", USER_NAME + "@test.local",
                "password", "whatever-123"))
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    @Test
    @DisplayName("创建：角色 id 不存在 → 40402（没有外键，必须手工校验）")
    void createUserRejectsUnknownRole() {
        post("/admin/users", adminToken, Map.of(
                "username", "au_bad_role",
                "password", "whatever-123",
                "roleIds", List.of(999999L)))
                .assertCode(ErrorCode.RESOURCE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("★ 创建：不能建出 SUPER_ADMIN（层级 >= 自己）→ 40301")
    void createUserCannotCreateSuperAdmin() {
        post("/admin/users", adminToken, Map.of(
                "username", "au_evil_admin",
                "password", "whatever-123",
                "roleIds", List.of(roleId("SUPER_ADMIN"))))
                .assertCode(ErrorCode.FORBIDDEN.getCode());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE username = ?", Integer.class, "au_evil_admin"))
                .as("越权请求被拦后不该留下半成品账号").isZero();
    }

    @Test
    @DisplayName("创建：参数校验（用户名过短 / 邮箱格式 / 密码过短）→ 40001")
    void createUserValidatesInput() {
        post("/admin/users", adminToken, Map.of("username", "ab", "password", "whatever-123"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post("/admin/users", adminToken, Map.of(
                "username", "au_bad_mail", "email", "not-an-email", "password", "whatever-123"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post("/admin/users", adminToken, Map.of("username", "au_short_pwd", "password", "123"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 改资料

    @Test
    @DisplayName("★ 改资料：只传 nickname 时其他字段不动（MP 忽略 null 字段）")
    void updateProfileKeepsUntouchedFields() {
        long id = createAccount("au_upd");
        String beforeEmail = jdbc.queryForObject(
                "SELECT email FROM `user` WHERE id = ?", String.class, id);

        put("/admin/users/" + id, adminToken, Map.of("nickname", "改名了")).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT nickname FROM `user` WHERE id = ?", String.class, id)).isEqualTo("改名了");
        assertThat(jdbc.queryForObject(
                "SELECT email FROM `user` WHERE id = ?", String.class, id)).isEqualTo(beforeEmail);
    }

    @Test
    @DisplayName("改资料：邮箱与他人重复 → 40902")
    void updateProfileRejectsDuplicateEmail() {
        long id = createAccount("au_upd_dup");
        put("/admin/users/" + id, adminToken, Map.of("email", ADMIN_NAME + "@test.local"))
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    @Test
    @DisplayName("改资料：用户不存在 → 40401")
    void updateProfileUnknownUserIsNotFound() {
        put("/admin/users/999999999", adminToken, Map.of("nickname", "x"))
                .assertCode(ErrorCode.USER_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 手机号唯一性

    @Test
    @DisplayName("★ 手机号：两个活跃账号不能相同 → 40902")
    void phoneMustBeUniqueAmongActiveUsers() {
        long first = createAccount("au_phone_a");
        long second = createAccount("au_phone_b");

        put("/admin/users/" + first, adminToken, Map.of("phone", "13800000001")).assertSuccess();
        put("/admin/users/" + second, adminToken, Map.of("phone", "13800000001"))
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    @Test
    @DisplayName("★ 手机号：账号注销后号码可以被别人用（和邮箱的规则刻意不同）")
    void phoneIsReusableAfterAccountDeleted() {
        long first = createAccount("au_phone_gone");
        long second = createAccount("au_phone_new");

        put("/admin/users/" + first, adminToken, Map.of("phone", "13800000002")).assertSuccess();

        // 注销第一个账号（逻辑删除），号码应当被释放
        jdbc.update("UPDATE `user` SET deleted = 1 WHERE id = ?", first);

        put("/admin/users/" + second, adminToken, Map.of("phone", "13800000002")).assertSuccess();
        assertThat(jdbc.queryForObject(
                "SELECT phone FROM `user` WHERE id = ?", String.class, second))
                .isEqualTo("13800000002");
    }

    @Test
    @DisplayName("手机号：空白字符串不会撞唯一索引（归一成 NULL）")
    void blankPhoneIsNormalizedToNull() {
        long first = createAccount("au_phone_blank_a");
        long second = createAccount("au_phone_blank_b");

        // 两个账号都传空串：如果不归一化，'' 也是值，第二次会撞索引
        put("/admin/users/" + first, adminToken, Map.of("phone", "   ")).assertSuccess();
        put("/admin/users/" + second, adminToken, Map.of("phone", "")).assertSuccess();
    }

    // ------------------------------------------------------------ 启停

    @Test
    @DisplayName("★ 禁用：状态置 0、撤销全部 refreshToken，旧 accessToken 立刻被拦（40303）")
    void disableUserRevokesTokensAndBlocksAccess() {
        long id = createAccount("au_dis");
        String token = loginRaw("/app/auth/login", "au_dis", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        assertThat(activeRefreshTokens(id)).as("登录后应有一条有效 refreshToken").isEqualTo(1);

        put("/admin/users/" + id + "/status", adminToken, Map.of("status", 0)).assertSuccess();

        assertThat(dbStatus(id)).isZero();
        assertThat(activeRefreshTokens(id)).as("禁用必须撤销全部 refreshToken").isZero();
        get("/app/auth/me", token).assertHttpStatus(403)
                .assertCode(ErrorCode.ACCOUNT_DISABLED.getCode());
    }

    @Test
    @DisplayName("★ 不能禁用自己 → 40301（否则会把自己锁在门外）")
    void cannotDisableSelf() {
        put("/admin/users/" + userId(ADMIN_NAME) + "/status", adminToken, Map.of("status", 0))
                .assertCode(ErrorCode.FORBIDDEN.getCode());

        assertThat(dbStatus(userId(ADMIN_NAME))).isEqualTo(1);
    }

    @Test
    @DisplayName("启停：状态只能是 0 / 1 → 否则 40001")
    void updateStatusValidatesValue() {
        put("/admin/users/" + userId(USER_NAME) + "/status", adminToken, Map.of("status", 2))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 分配角色

    @Test
    @DisplayName("★ 分配角色是全量覆盖：先清后插，传空数组即清空")
    void assignRolesReplacesAll() {
        long id = createAccount("au_role");

        put("/admin/users/" + id + "/roles", adminToken,
                Map.of("roleIds", List.of(roleId("USER"), roleId("ADMIN")))).assertSuccess();
        assertThat(dbRoles(id)).containsExactlyInAnyOrder("ADMIN", "USER");

        put("/admin/users/" + id + "/roles", adminToken, Map.of("roleIds", List.of()))
                .assertSuccess();
        assertThat(dbRoles(id)).as("空数组表示清空全部角色").isEmpty();
    }

    @Test
    @DisplayName("分配角色：角色 id 不存在 → 40402")
    void assignRolesRejectsUnknownRole() {
        long id = createAccount("au_role_bad");
        put("/admin/users/" + id + "/roles", adminToken, Map.of("roleIds", List.of(999999L)))
                .assertCode(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertThat(dbRoles(id)).containsExactly("USER");
    }

    @Test
    @DisplayName("★ 分配角色：不能把 SUPER_ADMIN 分出去（提权入口）→ 40301")
    void assignRolesCannotGrantSuperAdmin() {
        long id = createAccount("au_role_evil");
        put("/admin/users/" + id + "/roles", adminToken,
                Map.of("roleIds", List.of(roleId("SUPER_ADMIN"))))
                .assertCode(ErrorCode.FORBIDDEN.getCode());

        assertThat(dbRoles(id)).as("被拦后角色应保持原样").containsExactly("USER");
    }

    // ------------------------------------------------------------ 批量启停

    @Test
    @DisplayName("★ 批量启停：返回实际更新条数，全部落库")
    void batchStatusUpdatesAll() {
        long a = createAccount("au_b1");
        long b = createAccount("au_b2");

        ApiResult res = put("/admin/users/batch-status", adminToken,
                Map.of("ids", List.of(a, b), "status", 0)).assertSuccess();

        assertThat(res.data().asInt()).isEqualTo(2);
        assertThat(dbStatus(a)).isZero();
        assertThat(dbStatus(b)).isZero();
    }

    @Test
    @DisplayName("★ 批量启停：列表里混进自己会被静默跳过（不报错，也不自禁）")
    void batchStatusSkipsSelf() {
        long a = createAccount("au_b3");
        long me = userId(ADMIN_NAME);

        ApiResult res = put("/admin/users/batch-status", adminToken,
                Map.of("ids", List.of(a, me), "status", 0)).assertSuccess();

        assertThat(res.data().asInt()).isEqualTo(1);
        assertThat(dbStatus(a)).isZero();
        assertThat(dbStatus(me)).as("当前登录账号不该被禁用").isEqualTo(1);
    }

    @Test
    @DisplayName("批量启停：ids 为空 / 状态越界 / 超过 100 条 → 40001")
    void batchStatusValidatesInput() {
        put("/admin/users/batch-status", adminToken, Map.of("ids", List.of(), "status", 0))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        put("/admin/users/batch-status", adminToken, Map.of("ids", List.of(userId(USER_NAME)), "status", 2))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            tooMany.add(i);
        }
        put("/admin/users/batch-status", adminToken, Map.of("ids", tooMany, "status", 0))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 批量分配角色

    @Test
    @DisplayName("★ 批量分配角色：逐个覆盖，返回条数")
    void batchRolesAppliesToAll() {
        long a = createAccount("au_br1");
        long b = createAccount("au_br2");

        ApiResult res = put("/admin/users/batch-roles", adminToken,
                Map.of("ids", List.of(a, b), "roleIds", List.of(roleId("ADMIN")))).assertSuccess();

        assertThat(res.data().asInt()).isEqualTo(2);
        assertThat(dbRoles(a)).containsExactly("ADMIN");
        assertThat(dbRoles(b)).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("批量分配角色：roleIds 传空数组表示全部清空")
    void batchRolesClearsWhenEmpty() {
        long a = createAccount("au_br3");
        put("/admin/users/batch-roles", adminToken,
                Map.of("ids", List.of(a), "roleIds", List.of())).assertSuccess();

        assertThat(dbRoles(a)).isEmpty();
    }

    @Test
    @DisplayName("★ 批量分配角色：同样不能分 SUPER_ADMIN → 40301")
    void batchRolesCannotGrantSuperAdmin() {
        long a = createAccount("au_br4");
        put("/admin/users/batch-roles", adminToken,
                Map.of("ids", List.of(a), "roleIds", List.of(roleId("SUPER_ADMIN"))))
                .assertCode(ErrorCode.FORBIDDEN.getCode());

        assertThat(dbRoles(a)).containsExactly("USER");
    }

    // ------------------------------------------------------------ 两层防护

    @Test
    @DisplayName("★ 普通用户访问管理端 → 403（第一层：URL 规则）")
    void normalUserCannotAccessAdminEndpoints() {
        String userToken = loginAsUser();
        get("/admin/users", userToken).assertHttpStatus(403);
        post("/admin/users", userToken, Map.of("username", "x", "password", "whatever-123"))
                .assertHttpStatus(403);
    }

    @Test
    @DisplayName("★ ADMIN 能登录管理端、能看列表，但没有 assign-role / create 权限码 → 403（第二层）")
    void adminWithoutPermissionCodeIsForbidden() {
        post("/admin/users", adminToken, Map.of(
                "username", "au_admin_role",
                "password", TEST_PASSWORD,
                "roleIds", List.of(roleId("ADMIN")))).assertSuccess();

        String adminRoleToken = loginRaw("/admin/auth/login", "au_admin_role", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        // 有 admin:user:list
        get("/admin/users", adminRoleToken).assertSuccess();

        // 没有 admin:user:create / admin:user:assign-role
        post("/admin/users", adminRoleToken, Map.of(
                "username", "au_by_admin", "password", "whatever-123"))
                .assertHttpStatus(403);
        put("/admin/users/batch-roles", adminRoleToken, Map.of(
                "ids", List.of(userId(USER_NAME)), "roleIds", List.of(roleId("USER"))))
                .assertHttpStatus(403);
    }

    // ------------------------------------------------------------ 辅助

    /** 取记录里 username 命中的那条；没有则返回 null。 */
    private JsonNode findRecord(JsonNode records, String username) {
        for (JsonNode node : records) {
            if (username.equals(node.path("username").asText())) {
                return node;
            }
        }
        return null;
    }

    /** 把字符串数组转成 List —— JsonNode 直接断言 contains 会踩类型推导的坑。 */
    private List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private long roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code = ?", Long.class, code);
    }

    private int dbStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM `user` WHERE id = ?", Integer.class, userId);
    }

    private int activeRefreshTokens(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }

    private List<String> dbRoles(long userId) {
        return jdbc.queryForList("""
                SELECT r.code FROM sys_role r
                JOIN sys_user_role ur ON ur.role_id = r.id
                WHERE ur.user_id = ?
                ORDER BY r.code
                """, String.class, userId);
    }
}
