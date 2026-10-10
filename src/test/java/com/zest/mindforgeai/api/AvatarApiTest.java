package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 头像：上传 + 公开读。
 *
 * <p>这个功能的风险不在「能不能传上去」，而在<b>公开读那个接口</b> ——
 * 它是整个用户端唯一一个不需要登录的接口（{@code <img src>} 带不上
 * Authorization 头），而且 {@code sys_file.id} 是可枚举的。
 * 所以「不能顺着它把简历 / 文档读走」这条必须有用例守着。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("头像")
class AvatarApiTest extends AbstractApiTest {

    @LocalServerPort
    private int port;

    private static final byte[] FAKE_PNG = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

    private ApiResult uploadAvatar(String token, String filename, byte[] content) {
        return upload("/app/user/avatar", token, "file", filename, content, "image/png");
    }

    /** 不跟随 302 —— TestRestTemplate 默认会跟，那样就看不到 Location 了。 */
    private HttpURLConnection rawGet(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://localhost:" + port + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        return conn;
    }

    // ---------- 上传 ----------

    @Test
    @DisplayName("上传成功：avatarUrl 指向后端路径，sys_file 记了 biz_type=avatars")
    void uploadSucceeds() {
        String token = loginAsUser();

        ApiResult result = uploadAvatar(token, "头像.png", FAKE_PNG).assertSuccess().assertCamelCase();

        String avatarUrl = result.str("avatarUrl");
        assertThat(avatarUrl).startsWith("/api/app/avatars/");

        String fileId = avatarUrl.substring("/api/app/avatars/".length());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT biz_type, content_type, file_size, user_id FROM sys_file WHERE id = ?",
                Long.valueOf(fileId));
        assertThat(row.get("biz_type")).as("★ 判断「这是不是头像」只能靠这一列").isEqualTo(FileService.BIZ_AVATAR);
        assertThat(row.get("user_id")).isEqualTo(userId(USER_NAME));
    }

    @Test
    @DisplayName("★ avatarUrl 存的是后端路径，不是 OSS 预签名 URL（预签名会过期）")
    void avatarUrlIsStablePathNotPresignedUrl() {
        String token = loginAsUser();
        uploadAvatar(token, "头像.png", FAKE_PNG).assertSuccess();

        String avatarUrl = jdbc.queryForObject(
                "SELECT avatar_url FROM `user` WHERE username = ?", String.class, USER_NAME);

        assertThat(avatarUrl)
                .as("存预签名 URL 的话过几天头像就全裂了")
                .startsWith("/api/app/avatars/")
                .doesNotContain("test.invalid")
                .doesNotContain("Signature")
                .doesNotContain("Expires");
    }

    @Test
    @DisplayName("★ 非图片类型 → 42201（头像读取是公开的，口子越小越好）")
    void nonImageIsRejected() {
        uploadAvatar(loginAsUser(), "简历.pdf", FAKE_PNG)
                .assertCode(ErrorCode.FILE_TYPE_UNSUPPORTED.getCode());
    }

    @Test
    @DisplayName("超过 2MB → 41301")
    void oversizedIsRejected() {
        byte[] big = new byte[(int) (2 * 1024 * 1024) + 1];

        uploadAvatar(loginAsUser(), "大图.png", big)
                .assertCode(ErrorCode.FILE_TOO_LARGE.getCode());
    }

    @Test
    @DisplayName("空文件 → 40001")
    void emptyFileIsRejected() {
        uploadAvatar(loginAsUser(), "空.png", new byte[0])
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("不带 token → 401")
    void uploadRequiresAuth() {
        uploadAvatar(null, "头像.png", FAKE_PNG).assertHttpStatus(401);
    }

    @Test
    @DisplayName("换成另一个文件后 avatarUrl 跟着变（URL 变了，浏览器不会拿旧缓存）")
    void replacingAvatarChangesUrl() {
        String token = loginAsUser();
        String first = uploadAvatar(token, "一.png", FAKE_PNG).assertSuccess().str("avatarUrl");
        String second = uploadAvatar(token, "二.png", "another-png".getBytes(StandardCharsets.UTF_8))
                .assertSuccess().str("avatarUrl");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("上传头像记了审计")
    void uploadIsAudited() {
        uploadAvatar(loginAsUser(), "头像.png", FAKE_PNG).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_log WHERE action = 'USER_UPLOAD_AVATAR'",
                Integer.class)).isEqualTo(1);
    }

    // ---------- ★ 公开读 ----------

    @Test
    @DisplayName("★ 不带 token 也能读（<img src> 带不上 Authorization 头）")
    void avatarIsPubliclyReadable() throws IOException {
        String avatarUrl = uploadAvatar(loginAsUser(), "头像.png", FAKE_PNG)
                .assertSuccess().str("avatarUrl");

        HttpURLConnection conn = rawGet(avatarUrl);
        assertThat(conn.getResponseCode())
                .as("这个接口必须公开，否则前端 <img> 根本显示不出头像")
                .isEqualTo(302);
        assertThat(conn.getHeaderField("Location")).isNotBlank();
    }

    @Test
    @DisplayName("★ 别人的头像也能读（头像本来就是公开信息）")
    void othersAvatarIsReadable() throws IOException {
        String avatarUrl = uploadAvatar(loginAsUser(), "头像.png", FAKE_PNG)
                .assertSuccess().str("avatarUrl");

        createAccount("t_other_user");
        String otherToken = loginRaw("/app/auth/login", "t_other_user", TEST_PASSWORD)
                .assertSuccess().str("accessToken");
        // 换个人、且完全不带 token 去读
        assertThat(otherToken).isNotBlank();
        assertThat(rawGet(avatarUrl).getResponseCode()).isEqualTo(302);
    }

    @Test
    @DisplayName("★★ 非头像文件（简历）读不到 → 404（否则公开接口成了文件下载器）")
    void nonAvatarFileIsNotReadable() throws IOException {
        // 先造一个简历 —— 它会经 mock 的存储服务写进 sys_file，biz_type=resumes
        uploadResume(loginAsUser(), "我的简历.txt", "张伟\nJava").assertSuccess();

        Long resumeFileId = jdbc.queryForObject(
                "SELECT id FROM sys_file WHERE biz_type = 'resumes' ORDER BY id DESC LIMIT 1",
                Long.class);
        assertThat(resumeFileId).as("前置条件：得先有一个简历文件").isNotNull();

        HttpURLConnection conn = rawGet("/app/avatars/" + resumeFileId);
        assertThat(conn.getResponseCode())
                .as("★ 这是这个功能最关键的一条 —— 公开读只能读头像，"
                        + "否则任何人枚举 fileId 就能把别人的简历、文档拉走")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("不存在的 fileId → 404")
    void missingAvatarIsNotFound() throws IOException {
        assertThat(rawGet("/app/avatars/99999999").getResponseCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("★ 已逻辑删除的文件读不到 → 404")
    void deletedFileIsNotFound() throws IOException {
        String avatarUrl = uploadAvatar(loginAsUser(), "头像.png", FAKE_PNG)
                .assertSuccess().str("avatarUrl");
        long fileId = Long.parseLong(avatarUrl.substring("/api/app/avatars/".length()));

        jdbc.update("UPDATE sys_file SET deleted = 1, deleted_at = NOW() WHERE id = ?", fileId);

        assertThat(rawGet(avatarUrl).getResponseCode()).isEqualTo(404);
    }
}
