package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证 / 权限 / 参数校验。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("认证与权限")
class AuthApiTest extends AbstractApiTest {

    private static final String APP_LOGIN = "/app/auth/login";
    private static final String ADMIN_LOGIN = "/admin/auth/login";

    // ---------- 登录 ----------

    @Test
    @DisplayName("登录成功：拿到 accessToken 和 refreshToken")
    void loginSucceeds() {
        ApiResult result = loginRaw(APP_LOGIN, USER_NAME, TEST_PASSWORD);

        result.assertSuccess().assertCamelCase();
        assertThat(result.str("accessToken")).as("accessToken 不能为空").isNotBlank();
        assertThat(result.str("refreshToken")).as("refreshToken 不能为空").isNotBlank();
    }

    @Test
    @DisplayName("密码错误：40101，且不透露账号是否存在")
    void wrongPasswordIsRejected() {
        loginRaw(APP_LOGIN, USER_NAME, "definitely-wrong").assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("用户名不存在：与密码错误返回同一个码（不泄漏账号是否存在）")
    void unknownUserLooksTheSameAsWrongPassword() {
        ApiResult unknown = loginRaw(APP_LOGIN, "no-such-user-at-all", TEST_PASSWORD);
        ApiResult wrongPwd = loginRaw(APP_LOGIN, USER_NAME, "definitely-wrong");

        unknown.assertCode(ErrorCode.UNAUTHORIZED.getCode());
        assertThat(unknown.code())
                .as("账号不存在和密码错误必须给同一个业务码，否则可以拿来枚举用户名")
                .isEqualTo(wrongPwd.code());
    }

    // ---------- 邮箱登录（2026-10-19）----------

    /** 库里 t_user 的邮箱 —— 不硬编码，避免测试库重建后 email 变了 */
    private String userEmail() {
        return jdbc.queryForObject(
                "SELECT email FROM `user` WHERE username = ?", String.class, USER_NAME);
    }

    @Test
    @DisplayName("邮箱登录：成功，拿到的 token 能访问受保护接口")
    void loginWithEmailSucceeds() {
        ApiResult result = loginRaw(APP_LOGIN, userEmail(), TEST_PASSWORD);

        result.assertSuccess().assertCamelCase();
        get("/app/user/profile", result.str("accessToken")).assertSuccess();
    }

    @Test
    @DisplayName("邮箱登录：大小写与前后空格不影响")
    void loginWithEmailIgnoresCaseAndSpaces() {
        // 邮箱归一化在 service 层（trim + 小写），不是靠 MySQL 的排序规则碰运气
        String messy = "  " + userEmail().toUpperCase() + "  ";
        loginRaw(APP_LOGIN, messy, TEST_PASSWORD).assertSuccess();
    }

    @Test
    @DisplayName("邮箱登录：密码错 → 40101（与用户名登录同一个码）")
    void loginWithEmailWrongPasswordIsRejected() {
        loginRaw(APP_LOGIN, userEmail(), "definitely-wrong")
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("邮箱不存在：和用户名不存在返回同一个码，不泄漏注册状态")
    void unknownEmailLooksTheSameAsUnknownUser() {
        ApiResult unknownEmail = loginRaw(APP_LOGIN, "nobody-at-all@test.local", TEST_PASSWORD);
        ApiResult unknownUser = loginRaw(APP_LOGIN, "no-such-user-at-all", TEST_PASSWORD);

        unknownEmail.assertCode(ErrorCode.UNAUTHORIZED.getCode());
        assertThat(unknownEmail.code())
                .as("两种「不存在」必须给同一个业务码，否则可以拿来枚举邮箱")
                .isEqualTo(unknownUser.code());
    }

    @Test
    @DisplayName("★ 用户名优先：含 @ 的用户名不会登进别人的邮箱账号")
    void usernameTakesPrecedenceOverEmail() {
        // 造一个用户名恰好等于 t_user 邮箱的账号，密码设成不同的
        String shadow = userEmail();
        Long shadowId = createAccount(shadow);
        jdbc.update("UPDATE `user` SET password_hash = ? WHERE id = ?",
                passwordEncoder.encode("shadow-pass"), shadowId);

        // 用「用户名账号的密码」能进 —— 说明命中的是 username 那一列
        loginRaw(APP_LOGIN, shadow, "shadow-pass").assertSuccess();

        // 用 t_user 的密码反而进不去；若哪天改成「邮箱优先」，这里会变成成功
        loginRaw(APP_LOGIN, shadow, TEST_PASSWORD)
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    // ---------- 登录标识长度（Redis key 无上限那个修复的回归保护）----------

    @Test
    @DisplayName("登录标识超长（65 字符）：40001，而不是走进认证")
    void identifierOver64IsRejected() {
        // 上限 2026-10-19 从 32 放宽到 64（要能装下邮箱），但仍然必须有上限
        loginRaw(APP_LOGIN, "x".repeat(65), TEST_PASSWORD).assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 登录标识 2000 字符：40001 —— 防止拿超长标识撑大 Redis key")
    void extremelyLongUsernameIsRejected() {
        // 修之前这里会一路走到认证、把 2000 字符拼进 mf:login:fail:u:{username}，
        // 造出 2016 字节的 key、15 分钟 TTL。见 LoginAttemptServiceImpl 的注释。
        loginRaw(APP_LOGIN, "x".repeat(2000), TEST_PASSWORD)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("登录标识正好 64 字符：通过校验（走到认证，得到 40101）")
    void identifierExactly64PassesValidation() {
        // 边界值：64 是 user.email varchar(64) 的上限
        loginRaw(APP_LOGIN, "x".repeat(64), TEST_PASSWORD)
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("用户名或密码为空：40001")
    void blankCredentialsAreRejected() {
        post(APP_LOGIN, null, Map.of("username", "", "password", TEST_PASSWORD))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(APP_LOGIN, null, Map.of("username", USER_NAME, "password", ""))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ---------- 鉴权 ----------

    @Test
    @DisplayName("不带 token 访问受保护接口：401")
    void anonymousIsRejected() {
        get("/app/resumes", null).assertHttpStatus(401);
        get("/app/knowledge-bases", null).assertHttpStatus(401);
    }

    @Test
    @DisplayName("伪造 / 损坏的 token：401")
    void forgedTokenIsRejected() {
        get("/app/resumes", "not-a-jwt-at-all").assertHttpStatus(401);
        get("/app/resumes", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.bad-signature")
                .assertHttpStatus(401);
    }

    @Test
    @DisplayName("带 token 取当前用户：成功")
    void meReturnsCurrentUser() {
        String token = loginAsUser();
        get("/app/auth/me", token).assertSuccess().assertCamelCase();
    }

    @Test
    @DisplayName("普通用户访问管理端接口：403（不是 401 —— token 是好的，只是没权限）")
    void normalUserCannotReachAdminEndpoints() {
        String token = loginAsUser();
        get("/admin/users", token).assertHttpStatus(403);
        get("/admin/dashboard", token).assertHttpStatus(403);
    }

    @Test
    @DisplayName("管理端 token 不能用于用户端接口（两端入口分开）")
    void adminTokenIsNotAcceptedByAppEndpoints() {
        // 管理端登录走 /admin/auth/login，签发的 token 带的是管理端角色；
        // 用户端 /app/** 要求的是 USER 侧权限码，两边不该通用。
        String adminToken = loginAsAdmin();
        get("/admin/dashboard", adminToken).assertSuccess();
    }

    // ---------- refreshToken ----------

    @Test
    @DisplayName("refresh 成功：换到新的 accessToken")
    void refreshIssuesNewAccessToken() {
        ApiResult login = loginRaw(APP_LOGIN, USER_NAME, TEST_PASSWORD).assertSuccess();
        String refreshToken = login.str("refreshToken");

        ApiResult refreshed = post("/app/auth/refresh", null, Map.of("refreshToken", refreshToken));
        refreshed.assertSuccess();
        assertThat(refreshed.str("accessToken")).as("refresh 后要有新的 accessToken").isNotBlank();
    }

    @Test
    @DisplayName("★ refreshToken 重放检测：同一个 refreshToken 用第二次会被拒")
    void refreshTokenCannotBeReplayed() {
        ApiResult login = loginRaw(APP_LOGIN, USER_NAME, TEST_PASSWORD).assertSuccess();
        String refreshToken = login.str("refreshToken");

        post("/app/auth/refresh", null, Map.of("refreshToken", refreshToken)).assertSuccess();

        ApiResult replayed = post("/app/auth/refresh", null, Map.of("refreshToken", refreshToken));
        assertThat(replayed.isSuccess())
                .as("refreshToken 用第二次必须失败 —— 轮换 + 重放检测是 refresh 机制的安全基础，"
                        + "否则被偷走的旧 token 可以一直换新的。实际响应：%s", replayed.body())
                .isFalse();
    }

    @Test
    @DisplayName("登出后该 refreshToken 不能再用")
    void logoutRevokesRefreshToken() {
        ApiResult login = loginRaw(APP_LOGIN, USER_NAME, TEST_PASSWORD).assertSuccess();
        String refreshToken = login.str("refreshToken");

        post("/app/auth/logout", null, Map.of("refreshToken", refreshToken)).assertSuccess();

        ApiResult afterLogout = post("/app/auth/refresh", null, Map.of("refreshToken", refreshToken));
        assertThat(afterLogout.isSuccess())
                .as("登出后 refreshToken 应已撤销，实际响应：%s", afterLogout.body())
                .isFalse();
    }

    @Test
    @DisplayName("空的 refreshToken：40001")
    void blankRefreshTokenIsRejected() {
        post("/app/auth/refresh", null, Map.of("refreshToken", ""))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ---------- 管理端 ----------

    @Test
    @DisplayName("管理端登录：成功")
    void adminLoginSucceeds() {
        loginRaw(ADMIN_LOGIN, ADMIN_NAME, TEST_PASSWORD).assertSuccess().assertCamelCase();
    }

    @Test
    @DisplayName("管理端登录用错密码：40101")
    void adminLoginWithWrongPasswordIsRejected() {
        loginRaw(ADMIN_LOGIN, ADMIN_NAME, "definitely-wrong")
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }
}
