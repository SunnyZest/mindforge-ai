package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 个人资料、公开信息、改密码、注册。
 *
 * <p>这几个接口「看起来很简单」，但每条后面都跟着一个安全决策，测试的价值就在这：
 * <ul>
 *   <li><b>改密码必须验旧密码</b> —— 只认 accessToken 的话，token 泄漏就能接管账号；</li>
 *   <li><b>改完要撤掉全部 refreshToken</b> —— 否则偷 cookie 的人改完密码还能继续用；</li>
 *   <li><b>公开信息是显式白名单</b> —— 入参 id 是别人能猜的，多返回一个邮箱就是一次泄漏；</li>
 *   <li><b>用户名/邮箱永久占用</b> —— 已注销账号的名字不能再注册，防冒名。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("个人资料与账号")
class UserProfileApiTest extends AbstractApiTest {

    private static final String PROFILE = "/app/user/profile";
    private static final String PASSWORD = "/app/user/password";

    private String token;
    private Long myUserId;

    @BeforeEach
    void setUp() {
        // 密码测试可能把 t_user 的密码改了，残留到下一轮 —— 先恢复再登录
        jdbc.update("UPDATE `user` SET password_hash = ? WHERE username = ?",
                passwordEncoder.encode(TEST_PASSWORD), USER_NAME);
        token = loginAsUser();
        myUserId = userId(USER_NAME);
    }

    // ------------------------------------------------------------ 个人资料

    @Test
    @DisplayName("资料页比认证视角多给邮箱/注册时间 —— 但【不给密码散列】")
    void profileCarriesDisplayFields() {
        var data = get(PROFILE, token).assertSuccess().data();

        assertThat(data.path("username").asText()).isEqualTo(USER_NAME);
        assertThat(data.path("email").asText()).isEqualTo(USER_NAME + "@test.local");
        assertThat(data.path("status").asInt()).isEqualTo(1);
        assertThat(data.path("createdAt").isNull()).isFalse();
        // 认证视角只给身份和权限（见 UserInfoVO），这里应该都没有
        assertThat(data.path("permissions").isMissingNode()).isTrue();
        assertThat(data.path("roles").isMissingNode()).isTrue();
        assertThat(data.path("passwordHash").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("改昵称：局部更新，头像不被一起清掉")
    void updateNicknameKeepsAvatar() {
        // 先给一个头像
        jdbc.update("UPDATE `user` SET avatar_url = ? WHERE id = ?",
                "/api/app/avatars/123", myUserId);

        var data = put(PROFILE, token, Map.of("nickname", "阿强")).assertSuccess().data();
        assertThat(data.path("nickname").asText()).isEqualTo("阿强");
        assertThat(data.path("avatarUrl").asText()).isEqualTo("/api/app/avatars/123");

        // 库里也确认另一列没被动
        assertThat(jdbc.queryForObject(
                "SELECT avatar_url FROM `user` WHERE id = ?", String.class, myUserId))
                .isEqualTo("/api/app/avatars/123");
    }

    @Test
    @DisplayName("只传头像 URL：昵称保持不变")
    void updateAvatarKeepsNickname() {
        jdbc.update("UPDATE `user` SET nickname = ? WHERE id = ?", "原名", myUserId);

        var data = put(PROFILE, token, Map.of("avatarUrl", "https://cdn.example.com/a.png"))
                .assertSuccess().data();

        assertThat(data.path("nickname").asText()).isEqualTo("原名");
        assertThat(data.path("avatarUrl").asText()).isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    @DisplayName("两个字段都不传：空操作，返回原值（不报错）")
    void updateWithNothingIsNoOp() {
        jdbc.update("UPDATE `user` SET nickname = ? WHERE id = ?", "别动我", myUserId);

        var data = put(PROFILE, token, Map.of()).assertSuccess().data();

        assertThat(data.path("nickname").asText()).isEqualTo("别动我");
    }

    @Test
    @DisplayName("★ 资料接口改不了用户名/邮箱（DTO 根本没这俩字段，多传也被忽略）")
    void cannotChangeUsernameOrEmail() {
        put(PROFILE, token, Map.of(
                "username", "hacked",
                "email", "hacked@evil.com",
                "status", 0)).assertSuccess();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT username, email, status FROM `user` WHERE id = ?", myUserId);
        assertThat(row.get("username")).isEqualTo(USER_NAME);
        assertThat(row.get("email")).isEqualTo(USER_NAME + "@test.local");
        // status 也改不了 —— 禁用账号是管理端的权限
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("昵称超过 64 字 → 40001")
    void nicknameIsLengthCapped() {
        put(PROFILE, token, Map.of("nickname", "x".repeat(65)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresAuth() {
        get(PROFILE, null).assertHttpStatus(401);
        put(PROFILE, null, Map.of("nickname", "x")).assertHttpStatus(401);
    }

    // ------------------------------------------------------------ 公开信息

    @Test
    @DisplayName("公开信息只给 id/用户名/昵称/头像 —— 邮箱手机号一律不给")
    void publicProfileIsWhitelisted() {
        jdbc.update("UPDATE `user` SET phone = '13800000000' WHERE id = ?", myUserId);

        var data = get("/app/users/" + myUserId, token).assertSuccess().data();

        assertThat(data.path("id").asText()).isEqualTo(String.valueOf(myUserId));
        assertThat(data.path("username").asText()).isEqualTo(USER_NAME);
        assertThat(data.path("nickname").isNull()).isFalse();
        // 关键：这些字段一个都不能出现
        assertThat(data.path("email").isMissingNode()).isTrue();
        assertThat(data.path("phone").isMissingNode()).isTrue();
        assertThat(data.path("status").isMissingNode()).isTrue();
        assertThat(data.path("createdAt").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("别人也能看（这是公开页）—— 但只能看到白名单字段")
    void publicProfileIsVisibleToAnyone() {
        var data = get("/app/users/" + myUserId, loginAs("profile_reader")).assertSuccess().data();
        assertThat(data.path("username").asText()).isEqualTo(USER_NAME);
    }

    @Test
    @DisplayName("用户不存在 → 40401")
    void publicProfileOfMissingUserIsNotFound() {
        get("/app/users/999999", token).assertCode(ErrorCode.USER_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 改密码

    private record PwAccount(String token, Long userId) {
    }

    private PwAccount pwAccount() {
        createAccount("pw_test");
        String tk = loginRaw("/app/auth/login", "pw_test", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        return new PwAccount(tk, userId("pw_test"));
    }

    @Test
    @DisplayName("★ 改密码要验旧密码；改完旧密码登不进去、新密码能登")
    void changePasswordRequiresOldPassword() {
        PwAccount acc = pwAccount();
        String newPassword = "new-998877";

        put(PASSWORD, acc.token(), Map.of("oldPassword", TEST_PASSWORD, "newPassword", newPassword))
                .assertSuccess();

        // 旧密码失效
        assertThat(loginRaw("/app/auth/login", "pw_test", TEST_PASSWORD).code())
                .isNotZero();
        // 新密码能登
        assertThat(loginRaw("/app/auth/login", "pw_test", newPassword).assertSuccess()
                .str("accessToken")).isNotBlank();

        // 库里确实换了散列（而且是 BCrypt，不是明文）
        String hash = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?",
                String.class, acc.userId());
        assertThat(hash).isNotEqualTo(TEST_PASSWORD).startsWith("$2");
    }

    @Test
    @DisplayName("★ 改密码后【全部 refreshToken 被撤】—— 偷 cookie 的人改完密码也登不上")
    void changePasswordRevokesRefreshTokens() {
        PwAccount acc = pwAccount();
        // 先登录一次拿一对 token
        ApiResult first = loginRaw("/app/auth/login", "pw_test", TEST_PASSWORD).assertSuccess();
        String oldRefresh = first.str("refreshToken");

        put(PASSWORD, acc.token(), Map.of("oldPassword", TEST_PASSWORD, "newPassword", "fresh-123456"))
                .assertSuccess();

        // 旧 refreshToken 不能再换了（旋转机制下重放本身也会被拒）
        ApiResult after = post("/app/auth/refresh", null, Map.of("refreshToken", oldRefresh));
        assertThat(after.code()).isNotZero();

        // 库里这一行也标记成撤销/已用
        Integer alive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, acc.userId());
        assertThat(alive).isZero();
    }

    @Test
    @DisplayName("旧密码不对 → 40001，密码没被动")
    void wrongOldPasswordIsRejected() {
        PwAccount acc = pwAccount();
        put(PASSWORD, acc.token(), Map.of("oldPassword", "wrong-old", "newPassword", "fresh-123456"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        // 密码没被改掉
        assertThat(loginRaw("/app/auth/login", "pw_test", TEST_PASSWORD).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("新密码和旧密码一样 → 40001（否则「改了密码」是个假动作）")
    void samePasswordIsRejected() {
        PwAccount acc = pwAccount();
        put(PASSWORD, acc.token(), Map.of("oldPassword", TEST_PASSWORD, "newPassword", TEST_PASSWORD))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("新密码少于 6 位 / 旧密码为空 → 40001")
    void passwordIsValidated() {
        PwAccount acc = pwAccount();
        put(PASSWORD, acc.token(), Map.of("oldPassword", TEST_PASSWORD, "newPassword", "123"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        put(PASSWORD, acc.token(), Map.of("oldPassword", " ", "newPassword", "fresh-123456"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("POST /app/user/password 与 PUT 行为一致（两个入口同一套逻辑）")
    void postPasswordAliasWorks() {
        PwAccount acc = pwAccount();
        post(PASSWORD, acc.token(), Map.of("oldPassword", TEST_PASSWORD, "newPassword", "alias-123456"))
                .assertSuccess();

        assertThat(loginRaw("/app/auth/login", "pw_test", "alias-123456").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("/app/auth/password 也是同一个改密码入口")
    void authPasswordEndpointWorks() {
        PwAccount acc = pwAccount();
        post("/app/auth/password", acc.token(),
                        Map.of("oldPassword", TEST_PASSWORD, "newPassword", "authp-123456"))
                .assertSuccess();

        assertThat(loginRaw("/app/auth/login", "pw_test", "authp-123456").isSuccess()).isTrue();
    }

    // ------------------------------------------------------------ 注册

    @Test
    @DisplayName("注册成功：默认角色 USER，拿到的权限是「用户端」那一套")
    void registerCreatesUserWithDefaultRole() {
        String username = "reg_newbie";
        String email = username + "@test.local";

        var data = post("/app/auth/register", null, Map.of(
                        "username", username,
                        "email", email,
                        "code", requestEmailCode(email, "REGISTER"),
                        "password", "reg-123456"))
                .assertSuccess().data();

        assertThat(data.path("username").asText()).isEqualTo(username);
        // 昵称默认取用户名 —— 省得用户注册完看到一空白名字
        assertThat(data.path("nickname").asText()).isEqualTo(username);
        assertThat(data.path("roles").get(0).asText()).isEqualTo("USER");
        assertThat(data.path("permissions").size()).isGreaterThan(0);

        // 验证码过了就是「已验证」
        assertThat(jdbc.queryForObject(
                "SELECT email_verified FROM `user` WHERE username = ?", Integer.class, username))
                .isEqualTo(1);

        // 真能登进去
        assertThat(loginRaw("/app/auth/login", username, "reg-123456").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("★ 邮箱必填：不传邮箱 → 40001（2026-10-18 起注册必须先验证邮箱）")
    void emailIsRequired() {
        post("/app/auth/register", null, Map.of(
                        "username", "no_email",
                        "code", "123456",
                        "password", "reg-123456"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("用户名重复 → 40901")
    void duplicateUsernameIsRejected() {
        String email = "dup_username@test.local";

        post("/app/auth/register", null, Map.of(
                        "username", USER_NAME,
                        "email", email,
                        "code", requestEmailCode(email, "REGISTER"),
                        "password", "reg-123456"))
                .assertCode(ErrorCode.USER_ALREADY_EXISTS.getCode());
    }

    @Test
    @DisplayName("邮箱重复 → 40902")
    void duplicateEmailIsRejected() {
        String email = USER_NAME + "@test.local";

        post("/app/auth/register", null, Map.of(
                        "username", "another_one",
                        "email", email,
                        "code", requestEmailCode(email, "REGISTER"),
                        "password", "reg-123456"))
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    @Test
    @DisplayName("★ 已注销（逻辑删除）账号的用户名也不能再注册 —— 防冒名")
    void deletedUsernameStaysOccupied() {
        // 造一个已注销账号
        Long victim = createAccount("reg_ghost");
        jdbc.update("UPDATE `user` SET deleted = 1 WHERE id = ?", victim);

        // 注意：基类的清理是物理 DELETE，所以这里验证的是「ignoreDeleted 计数」这条路径
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE username = ? AND deleted = 0",
                Integer.class, "reg_ghost")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE username = ?",
                Integer.class, "reg_ghost")).isEqualTo(1);

        post("/app/auth/register", null, Map.of(
                        "username", "reg_ghost",
                        "email", "reg_ghost@test.local",
                        "code", requestEmailCode("reg_ghost@test.local", "REGISTER"),
                        "password", "reg-123456"))
                .assertCode(ErrorCode.USER_ALREADY_EXISTS.getCode());
    }

    @Test
    @DisplayName("校验：用户名太短 / 邮箱格式错 / 密码太短 → 40001")
    void registerValidatesInput() {
        post("/app/auth/register", null, Map.of(
                        "username", "ab",
                        "email", "short@test.local",
                        "code", "123456",
                        "password", "reg-123456"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post("/app/auth/register", null, Map.of(
                        "username", "ok_user",
                        "email", "not-an-email",
                        "code", "123456",
                        "password", "reg-123456"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post("/app/auth/register", null, Map.of(
                        "username", "ok_user2",
                        "email", "ok_user2@test.local",
                        "code", "123456",
                        "password", "123"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 辅助

    private String loginAs(String username) {
        createAccount(username);
        return loginRaw("/app/auth/login", username, TEST_PASSWORD)
                .assertSuccess().data().path("accessToken").asText();
    }
}
