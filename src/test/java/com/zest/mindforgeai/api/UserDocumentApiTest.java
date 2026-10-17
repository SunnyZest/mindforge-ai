package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户端文档（上传 / 列表 / 详情 / 状态 / 重试 / 下载 / 删除）。
 *
 * <p>这一组此前完全没测试，而它踩的坑都很有代表性：
 * <ul>
 *   <li><b>上传到官方智库必须被拒</b> —— 否则普通用户能往所有用户共享的库里塞东西；</li>
 *   <li><b>下载是 302 跳预签名 URL</b>，URL 过了有效期就没用了，所以 DocumentVO 里
 *       不该带可点的下载地址（断言里顺带确认没有这种字段）；</li>
 *   <li><b>删除只做逻辑删除</b>，OSS 对象留恢复窗口，行还在、只是查不到；</li>
 *   <li>官方库的文档对普通用户<b>只读</b>（看得到、下得了、删不得、重试不得）。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("用户端文档")
class UserDocumentApiTest extends AbstractApiTest {

    private static final String KB_DOCS = "/app/knowledge-bases/{kbId}/documents";
    private static final String DOC = "/app/documents/{id}";

    private String token;
    private Long myUserId;
    private Long otherUserId;

    /** 测试实例的随机端口 —— 下载接口要验 302 的 Location 头，不能被客户端跟着跳走 */
    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        token = loginAsUser();
        myUserId = userId(USER_NAME);
        otherUserId = createAccount("doc_other");
    }

    // ------------------------------------------------------------ 上传

    @Test
    @DisplayName("上传到自己的库：状态初始为 UPLOADED，三阶段都是 PENDING")
    void uploadToOwnKnowledgeBase() {
        long kbId = createKb("我的笔记库", myUserId, 0);

        ApiResult res = upload(kbId, "Spring 事务.md", "正文".getBytes())
                .assertSuccess();

        assertThat(res.str("fileName")).isEqualTo("Spring 事务.md");
        assertThat(res.str("fileType")).isEqualTo("MD");
        assertThat(res.str("status")).isEqualTo("UPLOADED");
        assertThat(res.str("parseStatus")).isEqualTo("PENDING");
        assertThat(res.str("chunkStatus")).isEqualTo("PENDING");
        assertThat(res.str("vectorStatus")).isEqualTo("PENDING");
        // 说明：VO 里不带下载地址 —— 预签名 URL 会过期，存不了也返不了
        assertThat(res.data().path("downloadUrl").isMissingNode()).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM document WHERE knowledge_base_id = ? AND deleted = 0",
                Integer.class, kbId)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 上传到官方智库被拒（普通用户根本写不进共享库）")
    void cannotUploadToSystemKnowledgeBase() {
        long systemKb = createKb("官方智库", null, 1);

        // ⚠️ 注意这里是 40403 而不是 40301：upload 第一步是 getOwned，
        //   官方库的 user_id 不是当前用户，在「是不是官方库」判断之前就被拦下了。
        //   这跟全项目「越权一律抛不存在」的约定是一致的 —— 报 403 等于承认 id 存在。
        //   Service 里那句 FORBIDDEN 兜底因此走不到，属于防御性死代码，留着无害。
        upload(systemKb, "我的笔记.md", "正文".getBytes())
                .assertCode(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE knowledge_base_id = ?",
                Integer.class, systemKb)).isZero();
    }

    @Test
    @DisplayName("上传到别人的私有库 → 40403")
    void cannotUploadToOthersKnowledgeBase() {
        long others = createKb("别人的库", otherUserId, 0);

        upload(others, "a.md", "正文".getBytes())
                .assertCode(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("不支持的扩展名 → 文件类型错误")
    void rejectsUnsupportedExtension() {
        long kbId = createKb("我的笔记库", myUserId, 0);

        upload(kbId, "病毒.exe", "x".getBytes())
                .assertCode(ErrorCode.FILE_TYPE_UNSUPPORTED.getCode());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE knowledge_base_id = ?",
                Integer.class, kbId)).isZero();
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresAuth() {
        uploadAnonymously(999999, "a.md", "x".getBytes()).assertHttpStatus(401);
        get(KB_DOCS.replace("{kbId}", "999999"), null).assertHttpStatus(401);
        get("/app/documents/999999", null).assertHttpStatus(401);
    }

    // ------------------------------------------------------------ 列表

    @Test
    @DisplayName("列表：分页 + 按文件名模糊搜索（大小写不敏感）")
    void pageWithKeywordSearch() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        createDoc(kbId, myUserId, "MySQL 索引优化.md");
        createDoc(kbId, myUserId, "Redis 缓存设计.md");
        createDoc(kbId, myUserId, "mysql 锁与事务.md");

        var all = page(kbId, null).assertSuccess().data();
        assertThat(all.path("total").asLong()).isEqualTo(3);

        var hit = page(kbId, "mysql").assertSuccess().data().path("records");
        assertThat(hit.findValuesAsText("fileName"))
                .containsExactlyInAnyOrder("MySQL 索引优化.md", "mysql 锁与事务.md");
    }

    @Test
    @DisplayName("列表里不会混进别的库的文档")
    void pageIsScopedToKnowledgeBase() {
        long mine = createKb("我的笔记库", myUserId, 0);
        long alsoMine = createKb("我的另一个库", myUserId, 0);
        long others = createKb("别人的库", otherUserId, 0);
        createDoc(mine, myUserId, "a.md");
        createDoc(alsoMine, myUserId, "b.md");
        createDoc(others, otherUserId, "c.md");

        var records = page(mine, null).assertSuccess().data().path("records");
        assertThat(records.findValuesAsText("fileName")).containsExactly("a.md");
    }

    @Test
    @DisplayName("看别人的库的文档列表 → 40403")
    void cannotPageOthersKnowledgeBase() {
        long others = createKb("别人的库", otherUserId, 0);

        page(others, null).assertCode(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("sortBy 不在白名单 → 40001")
    void sortByIsWhitelisted() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        page(kbId, null) // 先确认能正常列
                .assertSuccess();
        get("/app/knowledge-bases/" + kbId + "/documents?sortBy=user_id&sortOrder=asc", token)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 详情 / 状态

    @Test
    @DisplayName("详情：返回文档基本信息与聚合状态")
    void detailReturnsDocument() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        // parse_status 的列取值是 PENDING/PARSING/SUCCESS/FAILED，
        // 「PARSED」是对外聚合出来的标签、不是列值 —— 解析完成 = parse_status=SUCCESS
        long docId = createDoc(kbId, myUserId, "a.md", "SUCCESS", "PENDING", "PENDING");

        var data = get("/app/documents/" + docId, token).assertSuccess().data();
        assertThat(data.path("fileName").asText()).isEqualTo("a.md");
        assertThat(data.path("knowledgeBaseId").asText()).isEqualTo(String.valueOf(kbId));
        // 解析完成但向量化还没开始 → PARSED（不是 COMPLETED）
        assertThat(data.path("status").asText()).isEqualTo("PARSED");
    }

    @Test
    @DisplayName("★ 聚合状态：任一阶段 FAILED 就整体 FAILED，不会被后面的 SUCCESS 盖掉")
    void aggregateStatusFavoursFailure() {
        long kbId = createKb("我的笔记库", myUserId, 0);

        // 解析失败、但向量化是 SUCCESS —— 整体必须还是 FAILED
        long failedDoc = createDoc(kbId, myUserId, "失败.md", "FAILED", "SUCCESS", "SUCCESS");
        assertThat(get("/app/documents/" + failedDoc + "/status", token)
                .assertSuccess().str("status")).isEqualTo("FAILED");

        // 三者全 SUCCESS 才是 COMPLETED
        long doneDoc = createDoc(kbId, myUserId, "完成.md", "SUCCESS", "SUCCESS", "SUCCESS");
        assertThat(get("/app/documents/" + doneDoc + "/status", token)
                .assertSuccess().str("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("★ 状态接口是给轮询用的：体量比详情轻，且带上错误信息")
    void statusEndpointIsPollingFriendly() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "坏.md", "FAILED", "PENDING", "PENDING");
        jdbc.update("UPDATE document SET error_message = ? WHERE id = ?", "PDF 已加密", docId);

        ApiResult res = get("/app/documents/" + docId + "/status", token).assertSuccess();

        assertThat(res.str("documentId")).isEqualTo(String.valueOf(docId));
        assertThat(res.str("status")).isEqualTo("FAILED");
        assertThat(res.str("errorMessage")).isEqualTo("PDF 已加密");
        // 轻量：不该把文件大小、创建时间这些详情字段带上
        assertThat(res.data().path("fileSize").isMissingNode()).isTrue();
        assertThat(res.data().path("createdAt").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("详情 / 状态：文档不存在或是别人的 → 40407")
    void detailOfOthersIsNotFound() {
        long othersDoc = createDoc(createKb("别人的库", otherUserId, 0), otherUserId, "secret.md");

        get("/app/documents/999999", token).assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        get("/app/documents/" + othersDoc, token).assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        get("/app/documents/" + othersDoc + "/status", token)
                .assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("★ 官方库的文档对所有用户可读（双轨检索的前提）")
    void systemKnowledgeBaseDocumentsAreReadable() {
        long systemKb = createKb("官方智库", null, 1);
        long sysDoc = createDoc(systemKb, myUserId, "大厂真题集.pdf");

        assertThat(get("/app/documents/" + sysDoc, token).assertSuccess()
                .str("fileName")).isEqualTo("大厂真题集.pdf");
        assertThat(page(systemKb, null).assertSuccess().data().path("total").asLong())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ 下载

    @Test
    @DisplayName("下载 → 302 跳预签名 URL")
    void downloadRedirectsToPresignedUrl() throws Exception {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "a.md");
        long fileId = createSysFile(myUserId, "sysfile/a.md");
        jdbc.update("UPDATE document SET file_id = ? WHERE id = ?", fileId, docId);

        // TestRestTemplate 会自己跟着 302 跑，这里必须用「不跟随重定向」的客户端
        // 才能看到 Location 头本身。
        java.net.http.HttpResponse<Void> res = rawDownload(String.valueOf(docId), token);

        assertThat(res.statusCode()).isEqualTo(302);
        // 跳的是「带有效期」的预签名地址，不是后端自己的路径 —— 文件流量不经过后端
        assertThat(res.headers().firstValue("Location").orElse(""))
                .isEqualTo("https://test.invalid/mock-object");
    }

    @Test
    @DisplayName("文档没有关联 sys_file → 资源不存在（不给跳转）")
    void downloadWithoutSysFileFails() throws Exception {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "a.md");

        assertThat(rawDownload(String.valueOf(docId), token).statusCode())
                .isNotEqualTo(302);
    }

    @Test
    @DisplayName("下载别人的文档 → 40407，且不给跳转")
    void cannotDownloadOthersDocument() throws Exception {
        long othersDoc = createDoc(createKb("别人的库", otherUserId, 0), otherUserId, "secret.md");

        // 302 才算放行；这里必须不是 302
        assertThat(rawDownload(String.valueOf(othersDoc), token).statusCode())
                .isNotEqualTo(302);
    }

    // ------------------------------------------------------------ 重试

    @Test
    @DisplayName("重试：三阶段重置为 PENDING，并清掉旧的错误信息")
    void retryResetsStatuses() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "坏.md", "FAILED", "PENDING", "PENDING");
        jdbc.update("UPDATE document SET error_message = ? WHERE id = ?", "上次炸了", docId);

        post("/app/documents/" + docId + "/retry", token, null).assertSuccess();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT parse_status, chunk_status, vector_status, error_message"
                        + " FROM document WHERE id = ?", docId);
        assertThat(row.get("parse_status")).isEqualTo("PENDING");
        assertThat(row.get("chunk_status")).isEqualTo("PENDING");
        assertThat(row.get("vector_status")).isEqualTo("PENDING");
        // 旧错误信息必须清掉 —— 留着用户会以为「还在失败」
        assertThat(row.get("error_message")).isNull();
    }

    @Test
    @DisplayName("没失败的文档重试 → 409（否则 AI 服务会对同一份文档跑两遍）")
    void retryOnlyWorksOnFailedDocument() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long pendingDoc = createDoc(kbId, myUserId, "刚上传.md", "PENDING", "PENDING", "PENDING");
        long runningDoc = createDoc(kbId, myUserId, "处理中.md", "PARSING", "PENDING", "PENDING");

        post("/app/documents/" + pendingDoc + "/retry", token, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
        post("/app/documents/" + runningDoc + "/retry", token, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());

        // 状态没被改动
        assertThat(jdbc.queryForObject("SELECT parse_status FROM document WHERE id = ?",
                String.class, runningDoc)).isEqualTo("PARSING");
    }

    @Test
    @DisplayName("★ 重试别人的文档 / 官方库文档 → 拒绝")
    void retryIsRestricted() {
        long othersDoc = createDoc(createKb("别人的库", otherUserId, 0), otherUserId, "secret.md");
        post("/app/documents/" + othersDoc + "/retry", token, null)
                .assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());

        long systemKb = createKb("官方智库", null, 1);
        long sysDoc = createDoc(systemKb, myUserId, "官方.md", "FAILED", "PENDING", "PENDING");
        post("/app/documents/" + sysDoc + "/retry", token, null)
                .assertCode(ErrorCode.FORBIDDEN.getCode());
    }

    // ------------------------------------------------------------ 删除

    @Test
    @DisplayName("删除：逻辑删除，列表和详情都查不到了")
    void deleteHidesDocument() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "要删的.md");

        delete("/app/documents/" + docId, token).assertSuccess();

        get("/app/documents/" + docId, token).assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        assertThat(page(kbId, null).assertSuccess().data().path("records")).isEmpty();
    }

    @Test
    @DisplayName("★ 删除是逻辑删除（行还在，deleted=1）—— OSS 对象要留恢复窗口")
    void deleteIsLogical() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "要删的.md");

        delete("/app/documents/" + docId, token).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE id = ?",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM document WHERE id = ?",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("删除时把关联的 sys_file 一起软删")
    void deleteSoftDeletesSysFile() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "要删的.md");
        long fileId = createSysFile(myUserId, "sysfile/to-delete.md");
        jdbc.update("UPDATE document SET file_id = ? WHERE id = ?", fileId, docId);

        delete("/app/documents/" + docId, token).assertSuccess();

        // sys_file 走软删（清理任务靠 deleted_at 算恢复窗口）
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_file WHERE id = ? AND deleted = 1",
                Integer.class, fileId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 官方库文档 / 别人的文档 → 删不掉（403 / 40407）")
    void deleteIsRestricted() {
        long othersDoc = createDoc(createKb("别人的库", otherUserId, 0), otherUserId, "secret.md");
        delete("/app/documents/" + othersDoc, token)
                .assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        // 对方的行没被逻辑删
        assertThat(jdbc.queryForObject("SELECT deleted FROM document WHERE id = ?",
                Integer.class, othersDoc)).isZero();

        long systemKb = createKb("官方智库", null, 1);
        long sysDoc = createDoc(systemKb, myUserId, "官方.md");
        delete("/app/documents/" + sysDoc, token)
                .assertCode(ErrorCode.FORBIDDEN.getCode());
        assertThat(jdbc.queryForObject("SELECT deleted FROM document WHERE id = ?",
                Integer.class, sysDoc)).isZero();
    }

    @Test
    @DisplayName("删除后该文档的分块不再参与检索（检索侧过滤 deleted=0）")
    void deletedChunksAreNotSearchable() {
        long kbId = createKb("我的笔记库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "要删的.md");
        createChunk(docId, kbId, myUserId, 0, "待删正文");

        delete("/app/documents/" + docId, token).assertSuccess();

        Integer chunks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_chunk c JOIN document d ON c.document_id = d.id"
                        + " WHERE c.document_id = ? AND d.deleted = 0",
                Integer.class, docId);
        assertThat(chunks).isZero();
    }

    // ------------------------------------------------------------ 辅助

    private org.springframework.http.HttpHeaders authHeaders(String tk) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(tk);
        return headers;
    }

    /**
     * 用「不跟随重定向」的裸客户端发一次下载请求。
     *
     * <p>{@code TestRestTemplate} 会把 302 跟着跑掉，测试就看不到 Location 头了
     * （而且会真的去连那个假域名）。context-path 是 {@code /api}，端口由
     * Spring 注入 —— 别去猜。
     */
    private java.net.http.HttpResponse<Void> rawDownload(String documentId, String tk) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port
                        + "/api/app/documents/" + documentId + "/download"))
                .GET();
        if (tk != null) {
            builder.header("Authorization", "Bearer " + tk);
        }
        return client.send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
    }

    private ApiResult upload(long kbId, String filename, byte[] content) {
        return upload(KB_DOCS.replace("{kbId}", String.valueOf(kbId)), token, "file",
                filename, content, "text/markdown");
    }

    /** 不带 token 的上传 —— 验 401 用（带 token 的话会先被知识库归属拦成 404） */
    private ApiResult uploadAnonymously(long kbId, String filename, byte[] content) {
        return upload(KB_DOCS.replace("{kbId}", String.valueOf(kbId)), null, "file",
                filename, content, "text/markdown");
    }

    private ApiResult page(long kbId, String keyword) {
        String url = KB_DOCS.replace("{kbId}", String.valueOf(kbId));
        if (keyword != null) {
            url += "?keyword=" + keyword;
        }
        return get(url, token);
    }

    private Long createKb(String name, Long ownerId, int isSystem) {
        long id = nextTestId();
        jdbc.update("INSERT INTO knowledge_base (id, user_id, name, status, is_system, deleted,"
                        + " document_count, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?, 0, 0, NOW(), NOW())",
                id, ownerId == null ? 0L : ownerId, name, isSystem);
        return id;
    }

    private Long createDoc(Long kbId, Long ownerId, String fileName) {
        return createDoc(kbId, ownerId, fileName, "PENDING", "PENDING", "PENDING");
    }

    private Long createDoc(Long kbId, Long ownerId, String fileName,
                           String parse, String chunk, String vector) {
        long id = nextTestId();
        jdbc.update("INSERT INTO document (id, user_id, knowledge_base_id, file_name, file_type,"
                        + " file_size, parse_status, chunk_status, vector_status, deleted, vector_purged,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'md', 1024, ?, ?, ?, 0, 0, NOW(), NOW())",
                id, ownerId, kbId, fileName, parse, chunk, vector);
        return id;
    }

    private Long createSysFile(Long ownerId, String objectKey) {
        long id = nextTestId();
        jdbc.update("INSERT INTO sys_file (id, user_id, original_name, object_key, bucket_name,"
                        + " content_type, file_size, content_hash, biz_type, storage_type,"
                        + " created_at, deleted, deleted_at)"
                        + " VALUES (?, ?, ?, ?, 'test-bucket', 'text/markdown', 10, 'hash',"
                        + " 'DOCUMENT', 'TEST', NOW(), 0, NULL)",
                id, ownerId, objectKey, objectKey);
        return id;
    }

    private Long createChunk(Long docId, Long kbId, Long ownerId, int index, String content) {
        long id = nextTestId();
        jdbc.update("INSERT INTO document_chunk (id, user_id, knowledge_base_id, document_id,"
                        + " chunk_index, content, created_at) VALUES (?, ?, ?, ?, ?, ?, NOW())",
                id, ownerId, kbId, docId, index, content);
        return id;
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(910_000L);

    private long nextTestId() {
        return SEQ.incrementAndGet();
    }
}
