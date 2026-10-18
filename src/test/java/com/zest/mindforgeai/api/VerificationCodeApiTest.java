package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.service.VerificationCodeSender;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 邮箱验证码：{@code /api/app/auth/verification-code} 与两个使用它的流程。
 *
 * <p>★ 这个接口<b>必须公开</b>（未登录才需要发码），所以它是「邮件轰炸」的入口。
 * 用例的重点不是「码能不能发出去」，而是<b>三层闸门挡不挡得住</b>：
 * 60 秒冷却、每日上限、试错次数；以及码是不是<b>一次性</b>的。
 *
 * <p>发送通道在基类里被 mock 掉了 —— 测试绝不真发邮件。
 *
 * @author crx
 * @since 2026-10-18
 */
@DisplayName("用户端-邮箱验证码")
class VerificationCodeApiTest extends AbstractApiTest {

    private static final String SEND_PATH = "/app/auth/verification-code";

    // ------------------------------------------------------------ 发送

    @Test
    @DisplayName("★ 发码：写进 Redis，并通过邮件通道发出（通道被 mock，不会真发信）")
    void sendStoresCodeAndCallsSender() {
        String email = "code_user@test.local";

        sendCode(email, "REGISTER").assertSuccess();

        Object stored = redis.opsForHash().get("mf:code:EMAIL:REGISTER:" + email, "code");
        assertThat(stored).isNotNull();
        assertThat(String.valueOf(stored)).hasSize(6);

        verify(verificationCodeSender).send(eq(email), anyString(), eq("REGISTER"));
    }

    @Test
    @DisplayName("★ 60 秒冷却：同一邮箱连发第二次 → 42904")
    void secondSendWithinCooldownIsRejected() {
        String email = "cooldown@test.local";

        sendCode(email, "REGISTER").assertSuccess();
        sendCode(email, "REGISTER")
                .assertCode(ErrorCode.VERIFICATION_TOO_FREQUENT.getCode());
    }

    @Test
    @DisplayName("★ 每日上限：绕过冷却发满 10 条后，第 11 条被拦")
    void dailyLimitIsEnforced() {
        String email = "daily@test.local";

        for (int i = 0; i < 10; i++) {
            // 模拟「60 秒过去了」：直接删冷却 key，绕开冷却只测每日上限
            redis.delete("mf:code:cd:EMAIL:" + email);
            sendCode(email, "REGISTER").assertSuccess();
        }

        redis.delete("mf:code:cd:EMAIL:" + email);
        sendCode(email, "REGISTER")
                .assertCode(ErrorCode.VERIFICATION_TOO_FREQUENT.getCode());
    }

    @Test
    @DisplayName("发码：邮箱格式不对 → 40001（服务端也校验，不只靠前端）")
    void invalidEmailIsRejected() {
        sendCode("not-an-email", "REGISTER")
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 找回密码：邮箱没注册过 → 接口成功，但【不发信】")
    void resetPasswordEmailUnregisteredDoesNotSend() {
        // 没注册过的邮箱：能注册（createAccount 会建号），所以这里用一个凭空捏造的
        sendCode("ghost@nowhere.invalid", "RESET_PASSWORD").assertSuccess();

        verify(verificationCodeSender, never())
                .send(eq("ghost@nowhere.invalid"), anyString(), eq("RESET_PASSWORD"));

        // 已注册的同一批思路要能发 —— 证明不是整个坏了，只是那一个被跳过
        createAccount("reset_registered");
        sendCode("reset_registered@test.local", "RESET_PASSWORD").assertSuccess();
        verify(verificationCodeSender)
                .send(eq("reset_registered@test.local"), anyString(), eq("RESET_PASSWORD"));
    }

    @Test
    @DisplayName("★ 注册场景：邮箱是否注册过都不影响发码（新账号本来就不该已注册）")
    void registerSceneAlwaysSends() {
        // 基类的 t_user 已经占用 t_user@test.local，但注册场景照样发 —— 拦了会让用户永远收不到注册码
        sendCode(USER_NAME + "@test.local", "REGISTER").assertSuccess();

        verify(verificationCodeSender)
                .send(eq(USER_NAME + "@test.local"), anyString(), eq("REGISTER"));
    }

    @Test
    @DisplayName("发码：SMS 通道还没开通 → 40001（明确报错，不静默失败）")
    void smsChannelIsNotAvailableYet() {
        post(SEND_PATH, null, Map.of(
                "channel", "SMS",
                "target", "13800000000",
                "scene", "REGISTER"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 校验

    @Test
    @DisplayName("★ 码错 → 40003；连错 5 次后这条码作废，正确码也不能用了")
    void wrongCodeInvalidatesAfterMaxAttempts() {
        String email = "attempts@test.local";
        String code = requestEmailCode(email, "REGISTER");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            register("attempts_user", email, wrong, "reg-123456")
                    .assertCode(ErrorCode.VERIFICATION_CODE_INVALID.getCode());
        }

        // 第 6 次带正确的码 —— 也该失败，因为这条码已经作废
        register("attempts_user", email, code, "reg-123456")
                .assertCode(ErrorCode.VERIFICATION_CODE_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 码是一次性的：同一个码第二次用 → 40003")
    void codeIsSingleUse() {
        createAccount("once_user");
        String email = "once_user@test.local";
        String code = requestEmailCode(email, "RESET_PASSWORD");

        resetPassword(email, code, "first-pass-123").assertSuccess();
        resetPassword(email, code, "second-pass-123")
                .assertCode(ErrorCode.VERIFICATION_CODE_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 场景隔离：注册的码不能拿去重置密码")
    void scenesAreIsolated() {
        String email = "scene@test.local";
        String registerCode = requestEmailCode(email, "REGISTER");

        post("/app/auth/reset-password", null, Map.of(
                "email", email,
                "code", registerCode,
                "newPassword", "new-123456"))
                .assertCode(ErrorCode.VERIFICATION_CODE_INVALID.getCode());
    }

    // ------------------------------------------------------------ 找回密码

    @Test
    @DisplayName("★ 找回密码：改密成功、旧密码失效、旧 refreshToken 被撤销")
    void resetPasswordWorksAndRevokesTokens() {
        // ⚠️ 用临时账号，不能动基类的 t_user —— 基类 ensureAccount 只在账号不存在时才建、
        //    不会重置密码，改了它会让其他测试类全部登录不上。
        createAccount("reset_pwd");
        String email = "reset_pwd@test.local";
        String oldRefresh = loginRaw("/app/auth/login", "reset_pwd", TEST_PASSWORD)
                .assertSuccess().str("refreshToken");

        resetPassword(email, requestEmailCode(email, "RESET_PASSWORD"), "brand-new-123")
                .assertSuccess();

        // 新密码能登录
        loginRaw("/app/auth/login", "reset_pwd", "brand-new-123").assertSuccess();
        // 旧密码不能
        loginRaw("/app/auth/login", "reset_pwd", TEST_PASSWORD)
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
        // 旧 refreshToken 已撤销 —— 密码都可能被泄露了，不能让旧会话续命
        post("/app/auth/refresh", null, Map.of("refreshToken", oldRefresh))
                .assertCode(ErrorCode.TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 找回密码：邮箱没注册过也返回成功（否则就是「账号是否存在」的探测器）")
    void resetPasswordOnUnknownEmailReturnsSuccess() {
        String email = "nobody@test.local";
        String code = requestEmailCode(email, "RESET_PASSWORD");

        resetPassword(email, code, "whatever-123").assertSuccess();

        // 没有创建任何账号
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE email = ?", Integer.class, email)).isZero();
    }

    // ------------------------------------------------------------ 辅助

    private ApiResult sendCode(String email, String scene) {
        return post(SEND_PATH, null, Map.of(
                "channel", "EMAIL",
                "target", email,
                "scene", scene));
    }

    private ApiResult resetPassword(String email, String code, String newPassword) {
        return post("/app/auth/reset-password", null, Map.of(
                "email", email,
                "code", code,
                "newPassword", newPassword));
    }

    private ApiResult register(String username, String email, String code, String password) {
        return post("/app/auth/register", null, Map.of(
                "username", username,
                "email", email,
                "code", code,
                "password", password));
    }
}
