package com.zest.mindforgeai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.pojo.vo.StoredFileVO;
import com.zest.mindforgeai.service.FileStorageService;
import com.zest.mindforgeai.service.VerificationCodeSender;
import com.zest.mindforgeai.service.impl.ReminderMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * API 层测试基类。
 *
 * <p>起真实的 Spring 上下文 + 真实 HTTP（RANDOM_PORT），打的是自己的 8080 之外端口，
 * 所以过滤链、JWT、RBAC、参数校验、异常处理器走的都是生产路径 —— 这个项目的
 * 几个真 bug（JWT 过滤器里抛异常变 500、ASYNC 派发被安全过滤器拦）都只在
 * 「走完整过滤链」时才会暴露，用 MockMvc 切片测不出来。
 *
 * <p>两个外部依赖被 mock 掉，这是它能「秒级 + 免费 + 可重复」的关键：
 * <ul>
 *   <li>{@link AiServiceClient} —— 否则每个用例都要真调 Python + 大模型（几十秒、花钱）</li>
 *   <li>{@link FileStorageService} —— 否则上传用例会真往 OSS 传文件</li>
 * </ul>
 *
 * <p>⚠️ 打的是 {@code mindforge_test} 库（见 {@code application-test.yml}）。
 * 跑之前先确保它存在：
 * <pre>
 * mysql -uroot -p -e "DROP DATABASE IF EXISTS mindforge_test;
 *                     CREATE DATABASE mindforge_test
 *                     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
 * mysql -uroot -p mindforge_test &lt; mindforge-schema.sql
 * </pre>
 * ⚠️ 那个 schema 文件里有 {@code USE `mindforge`}，直接灌会打进开发库，
 * 得先把 {@code CREATE DATABASE} / {@code USE} 两句滤掉（见 README）。
 *
 * @author crx
 * @since 2026-10-10
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "test"})
public abstract class AbstractApiTest {

    /** 测试账号的密码。基类每次用例前把账号写进测试库，所以不用依赖 schema 的种子数据。 */
    protected static final String TEST_PASSWORD = "test-123456";
    protected static final String USER_NAME = "t_user";
    protected static final String ADMIN_NAME = "t_admin";

    private static final long ROLE_USER = 1L;
    private static final long ROLE_SUPER_ADMIN = 3L;

    /**
     * 每次用例前清空的表。
     *
     * <p>不含 {@code user} / {@code sys_user_role} / {@code sys_role*} —— 前两个由
     * {@link #ensureTestAccounts()} 维护，后两个是 schema 的种子数据。
     * 顺序无所谓：本项目所有表都没有外键。
     */
    private static final List<String> BUSINESS_TABLES = List.of(
            "chat_source", "chat_message", "conversation",
            "document_chunk", "document", "knowledge_base",
            "interview_question", "interview_report", "interview",
            "resume_skill", "resume_job_analysis", "salary_estimate", "resume",
            "job_event", "job_skill", "job", "job_offer",
            "sys_file", "sys_audit_log", "sys_alert", "sys_refresh_token", "ai_usage_record",
            // 回收站记录没有 deleted 列（恢复 / 彻底删除后直接物理删），只能物理清
            "recycle_bin",
            // sys_ai_model / sys_prompt 都没有 deleted 列，只能物理删
            "sys_ai_model", "sys_prompt"
    );

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockitoBean
    protected AiServiceClient aiServiceClient;

    @MockitoBean
    protected FileStorageService fileStorageService;

    /**
     * 验证码通道一律 mock 掉。
     * <p>
     * ⚠️ 不 mock 的话测试会真的去连 SMTP：要么因为没配授权码失败，
     * 要么（更糟）给真实邮箱发一堆验证码。
     */
    @MockitoBean
    protected VerificationCodeSender verificationCodeSender;

    /**
     * 日程提醒邮件同样一律 mock 掉。
     * <p>
     * ⚠️ 提醒任务每小时整点跑一次，测试进程如果正好在那个时刻执行到某条用例，
     * 真实 sender 会尝试连 SMTP —— 没配授权码就失败，配了就<b>真的给测试账号发信</b>。
     * mock 掉之后默认 {@code isConfigured()} 返回 false，提醒任务整轮跳过。
     */
    @MockitoBean
    protected ReminderMailSender reminderMailSender;

    @Autowired
    protected StringRedisTemplate redis;

    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(dataSource);
        clearBusinessData();
        clearVerificationKeys();
        clearLoginAttempts();
        clearQuotaKeys();
        ensureTestAccounts();
        stubFileStorage();
        stubVerificationSender();
    }

    /**
     * 清掉按用户计的 AI 额度计数（邀约识别每小时 20 次）。
     * <p>
     * ⚠️ 和登录失败计数一样会跨用例残留：限流用例会故意把计数顶到 20，
     * 不清的话后面所有用例都会拿到 42901，看起来像「接口坏了」。
     */
    private void clearQuotaKeys() {
        Set<String> keys = redis.keys("mf:invitation:extract:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * mock 的通道要告诉 service 自己是哪个通道。
     * <p>
     * ⚠️ 不配的话 {@code channel()} 返回 null —— 服务启动时按通道建索引会炸
     * （Map.copyOf 不接受 null 键），表现为「ApplicationContext 加载失败」，
     * 33 个用例集体 error，但堆栈里看不出跟验证码有关。
     */
    private void stubVerificationSender() {
        when(verificationCodeSender.channel()).thenReturn("EMAIL");
    }

    /**
     * 清掉验证码相关的 Redis key。
     * <p>
     * ⚠️ 必须清：60 秒冷却和「每天 10 条」都在 Redis 里跨用例残留，
     * 不清的话第二个用例发码会直接撞冷却（42904），看起来像功能坏了。
     */
    private void clearVerificationKeys() {
        Set<String> keys = redis.keys("mf:code:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * 清掉登录 / 改密码的失败计数。
     * <p>
     * ⚠️ 这些计数也跨用例残留：一个「密码错」的断言就 +1，阈值只有 5。
     * 同一台机器上短时间连跑几遍测试，就会出现「明明密码是对的却提示失败次数过多」——
     * 排查时极容易怀疑到登录逻辑本身。
     */
    private void clearLoginAttempts() {
        Set<String> keys = redis.keys("mf:login:fail:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        Set<String> pwdKeys = redis.keys("mf:pwd:fail:*");
        if (pwdKeys != null && !pwdKeys.isEmpty()) {
            redis.delete(pwdKeys);
        }
    }

    /**
     * 给被 mock 的 {@link FileStorageService} 配默认行为。
     *
     * <p>⚠️ 不配的话 mock 会返回 null，service 里读 {@code stored.getSize()} 直接 NPE ——
     * 表现是「上传接口 500 系统内部异常」，看着像产品 bug，其实是测试自己没配好。
     * 每个用例都配一遍很啰嗦，所以放在基类的 {@code @BeforeEach} 里。
     */
    private void stubFileStorage() {
        when(fileStorageService.upload(anyString(), any(byte[].class), any()))
                .thenAnswer(invocation -> {
                    StoredFileVO stored = new StoredFileVO();
                    stored.setObjectKey(invocation.getArgument(0));
                    stored.setBucketName("test-bucket");
                    stored.setStorageType("TEST");
                    stored.setSize(((byte[]) invocation.getArgument(1)).length);
                    return stored;
                });

        // 解析流程会拿预签名 URL 交给 Python 去下载文件。
        // 给一个一眼能看出是假地址的 URL —— 万一真有人去请求它，别指向任何真实主机。
        when(fileStorageService.presignedUrl(anyString(), any()))
                .thenReturn("https://test.invalid/mock-object");
    }

    // ---------- 数据准备 ----------

    private void clearBusinessData() {
        BUSINESS_TABLES.forEach(t -> jdbc.update("DELETE FROM " + t));
        // 归档表不在 BUSINESS_TABLES 里：它可能还不存在（首次运行时由 RecycleBinCleanupTask
        // 用 CREATE TABLE IF NOT EXISTS 建），直接 DELETE 会报表不存在
        try {
            jdbc.update("DELETE FROM deleted_data_archive");
        } catch (Exception ignored) {
            // 表还没建 —— 说明本进程还没跑过清理任务，也就没有归档需要清
        }
        // 用例自己注册的用户也要清掉，只留基类造的两个账号
        jdbc.update("DELETE FROM `user` WHERE username NOT IN (?, ?)", USER_NAME, ADMIN_NAME);
        // 上面删掉的用户可能还留着角色绑定（没有外键，不会级联）
        jdbc.update("DELETE FROM sys_user_role WHERE user_id NOT IN (SELECT id FROM `user`)");
    }

    private void ensureTestAccounts() {
        ensureAccount(USER_NAME, ROLE_USER);
        ensureAccount(ADMIN_NAME, ROLE_SUPER_ADMIN);
    }

    private void ensureAccount(String username, long roleId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            // 共享账号可能被某个用例改过（改邮箱、禁用、逻辑删除……）。
            // 这里每次归一回去，让用例之间不依赖执行顺序 ——
            // 实测踩过：一个用例把 t_user 的邮箱改成自己的，同包另一个用例的
            // 「邮箱不可改」断言就挂了，而且只在整包跑时挂、单跑那个类是绿的。
            // 只重置便宜的列，密码哈希不动（bcrypt 每次重算很慢，也没有用例改它）。
            jdbc.update("""
                    UPDATE `user`
                       SET email = ?, nickname = ?, status = 1, deleted = 0,
                           email_verified = 0, reminder_enabled = 1
                     WHERE username = ?
                    """, username + "@test.local", username, username);
            return;
        }
        long newUserId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("""
                INSERT INTO `user` (id, username, email, password_hash, nickname, status,
                                    created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, ?, 1, NOW(), NOW(), 0)
                """, newUserId, username, username + "@test.local", passwordEncoder.encode(TEST_PASSWORD), username);
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, created_at) VALUES (?, ?, ?, NOW())",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), newUserId, roleId);
    }

    /** 按用户名查 id —— 断言归属时用得上。 */
    protected Long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM `user` WHERE username = ?", Long.class, username);
    }

    /**
     * 额外造一个普通用户（密码同 {@link #TEST_PASSWORD}），并返回它的 id。
     * <p>测「归属校验」需要两个用户 —— 拿 A 的 token 去访问 B 的资源，
     * 必须返回 404 而不是 403（403 等于告诉对方「这个 id 存在，只是不是你的」）。
     */
    protected Long createAccount(String username) {
        ensureAccount(username, ROLE_USER);
        return userId(username);
    }

    /**
     * 走真实链路取一个邮箱验证码：调发码接口 → 从 Redis 读回来。
     * <p>
     * 发送通道已被 mock（不会真发信），但「生成 + 存 Redis + 冷却 + 每日计数」都是真的，
     * 比直接往 Redis 塞一个假码更接近线上。
     *
     * @param scene {@code REGISTER} / {@code RESET_PASSWORD}
     */
    protected String requestEmailCode(String email, String scene) {
        post("/app/auth/verification-code", null, Map.of(
                "channel", "EMAIL",
                "target", email,
                "scene", scene)).assertSuccess();

        Object code = redis.opsForHash().get(
                "mf:code:EMAIL:" + scene + ":" + email.trim().toLowerCase(), "code");
        if (code == null) {
            throw new IllegalStateException("验证码没有写进 Redis：" + email);
        }
        return String.valueOf(code);
    }

    // ---------- 登录 ----------

    /** 用基类造的普通用户登录，返回 accessToken。 */
    protected String loginAsUser() {
        return login("/app/auth/login", USER_NAME);
    }

    /** 用基类造的超级管理员登录（管理端入口），返回 accessToken。 */
    protected String loginAsAdmin() {
        return login("/admin/auth/login", ADMIN_NAME);
    }

    /** 登录并返回完整响应 —— 要断言 token 结构、或故意用错密码时用这个。 */
    protected ApiResult loginRaw(String path, String username, String password) {
        return post(path, null, Map.of("username", username, "password", password));
    }

    private String login(String path, String username) {
        ApiResult result = loginRaw(path, username, TEST_PASSWORD);
        result.assertSuccess();
        String token = result.str("accessToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("登录成功但没拿到 accessToken，响应：" + result.body());
        }
        return token;
    }

    // ---------- HTTP ----------

    protected ApiResult get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected ApiResult post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    protected ApiResult put(String path, String token, Object body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    protected ApiResult delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, token, null);
    }

    private ApiResult exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = rest.exchange(
                path, method, new HttpEntity<>(body, headers), JsonNode.class);
        return new ApiResult(response.getStatusCode().value(), response.getBody());
    }

    /**
     * multipart 上传（简历 / 文档走这个）。
     *
     * @param partName 表单字段名（简历是 {@code file}）
     */
    protected ApiResult upload(String path, String token, String partName,
                               String filename, byte[] content, String contentType) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(partName, new HttpEntity<>(resource, partHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = rest.exchange(
                path, HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);
        return new ApiResult(response.getStatusCode().value(), response.getBody());
    }

    /** 上传一份文本简历，返回响应。 */
    protected ApiResult uploadResume(String token, String filename, String text) {
        return upload("/app/resumes", token, "file", filename,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain");
    }
}
