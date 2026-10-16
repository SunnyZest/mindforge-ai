package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义检索（搜索页）。
 *
 * <p>这个接口此前<b>完全没测试</b>，2026-10-16 因为搜索页要「带正文」而改了它的契约，
 * 顺手把测试补上。重点两块：
 * <ul>
 *   <li><b>检索范围</b>（本次新加的语义）—— 不传知识库 id 时要并上「我的全部库 + 官方智库」，
 *       且<b>绝不能碰到别人的库</b>；</li>
 *   <li>结果装配 —— 必须带正文（搜索页的主体），以及来源库名与官方标记。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("语义检索")
class RagSearchApiTest extends AbstractApiTest {

    private static final String PATH = "/app/rag/search";

    private String token;
    private Long myUserId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        token = loginAsUser();
        myUserId = userId(USER_NAME);
        otherUserId = createAccount("rag_other");
    }

    // ------------------------------------------------------------ 检索范围

    @Test
    @DisplayName("★ 一个可搜的库都没有 → 返回空结果，且【不调用 AI 服务】（别白花一次嵌入的钱）")
    void emptyScopeSkipsAiCall() {
        ApiResult res = post(PATH, token, Map.of("query", "随便搜点什么")).assertSuccess();

        assertThat(res.data().path("results")).isEmpty();
        assertThat(res.data().path("searchedKbCount").asInt()).isZero();
        // 关键是「没调」——否则每次搜索都白烧一次向量化的钱
        verify(aiServiceClient, never()).search(any());
    }

    @Test
    @DisplayName("★ 不传 knowledgeBaseId → 范围是「我的全部知识库 + 官方智库」")
    void scopeCoversAllMinePlusSystem() {
        long mine1 = createKb("我的库A", myUserId, 0);
        long mine2 = createKb("我的库B", myUserId, 0);
        long disabled = createKb("我的停用库", myUserId, 0);
        jdbc.update("UPDATE knowledge_base SET status = 0 WHERE id = ?", disabled);
        long others = createKb("别人的库", otherUserId, 0);
        long system = createKb("官方智库", null, 1);

        // 可搜的库必须「有 SUCCESS 文档」才被纳入
        makeSearchable(mine1);
        makeSearchable(mine2);
        makeSearchable(disabled);
        makeSearchable(others);
        makeSearchable(system);

        stubSearch();

        String kbIds = capturedKbIds();
        assertThat(kbIds).contains(String.valueOf(mine1)).contains(String.valueOf(mine2))
                .contains(String.valueOf(system));
        // 数据隔离红线：别人的库、自己停用的库都不能进去
        assertThat(kbIds).doesNotContain(String.valueOf(others))
                .doesNotContain(String.valueOf(disabled));
    }

    @Test
    @DisplayName("传了某个库 → 只搜它 + 官方智库（与聊天链路一致的双轨）")
    void scopeWithExplicitKbIsDualTrack() {
        long mine1 = createKb("我的库A", myUserId, 0);
        long mine2 = createKb("我的库B", myUserId, 0);
        long system = createKb("官方智库", null, 1);
        makeSearchable(mine1);
        makeSearchable(mine2);
        makeSearchable(system);

        stubSearch();

        String kbIds = capturedKbIds(String.valueOf(mine1));
        assertThat(kbIds).contains(String.valueOf(mine1));
        assertThat(kbIds).doesNotContain(String.valueOf(mine2));
    }

    @Test
    @DisplayName("★ 传别人的知识库 → 404（不区分「不存在」与「不是你的」，避免泄露 id）")
    void cannotSearchOthersKb() {
        long others = createKb("别人的库", otherUserId, 0);
        makeSearchable(others);

        post(PATH, token, Map.of("knowledgeBaseId", String.valueOf(others), "query", "x"))
                .assertCode(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 结果装配

    @Test
    @DisplayName("★ 结果必须带【正文】+ 来源知识库名 + 官方标记（搜索页的主体）")
    void hitsCarryContentAndKbInfo() {
        long mine = createKb("我的库A", myUserId, 0);
        long system = createKb("官方智库", null, 1);
        long docMine = createDoc(mine, myUserId, "我的笔记.md");
        long docSys = createDoc(system, myUserId, "大厂真题集.pdf");
        long chunkMine = createChunk(docMine, mine, myUserId, 0, "MySQL 索引失效的三种典型场景");
        long chunkSys = createChunk(docSys, system, myUserId, 3, "字节跳动一面真题解析");

        stubSearch(
                new AiServiceClient.Source("v1", String.valueOf(docMine), String.valueOf(mine), 0,
                        "MySQL 索引失效的三种典型场景", 0.91),
                new AiServiceClient.Source("v2", String.valueOf(docSys), String.valueOf(system), 3,
                        "字节跳动一面真题解析", 0.77));

        var results = post(PATH, token, Map.of("query", "索引")).assertSuccess().data().path("results");
        assertThat(results).hasSize(2);

        var first = results.get(0);
        assertThat(first.path("content").asText()).isEqualTo("MySQL 索引失效的三种典型场景");
        assertThat(first.path("documentName").asText()).isEqualTo("我的笔记.md");
        assertThat(first.path("knowledgeBaseName").asText()).isEqualTo("我的库A");
        assertThat(first.path("fromSystemKb").asBoolean()).isFalse();
        assertThat(first.path("chunkId").asLong()).isEqualTo(chunkMine);
        assertThat(first.path("score").asDouble()).isEqualTo(0.91);

        var second = results.get(1);
        assertThat(second.path("fromSystemKb").asBoolean()).isTrue();
        assertThat(second.path("knowledgeBaseName").asText()).isEqualTo("官方智库");
        assertThat(second.path("chunkId").asLong()).isEqualTo(chunkSys);
    }

    @Test
    @DisplayName("reranked 如实透传（请求了重排 ≠ 重排生效）")
    void rerankedIsPassedThrough() {
        long mine = createKb("我的库A", myUserId, 0);
        makeSearchable(mine);
        stubSearch(false);

        assertThat(post(PATH, token, Map.of("enableRerank", true, "query", "x"))
                .assertSuccess().data().path("reranked").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("悬空引用（向量库有、MySQL 分块已不在）直接跳过，不让前端点进去 404")
    void danglingVectorIsSkipped() {
        long mine = createKb("我的库A", myUserId, 0);
        makeSearchable(mine);

        stubSearch(
                // 文档不存在
                new AiServiceClient.Source("v1", "999999", String.valueOf(mine), 0, "野的", 0.9),
                // 文档在，但 chunkIndex 对不上（分块被重处理过）
                new AiServiceClient.Source("v2", String.valueOf(createDoc(mine, myUserId, "a.md")),
                        String.valueOf(mine), 99, "错位的", 0.8));

        assertThat(post(PATH, token, Map.of("query", "x")).assertSuccess()
                .data().path("results")).isEmpty();
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("检索内容为空 → 40001；topK 越界 → 40001")
    void validation() {
        post(PATH, token, Map.of("query", "   "))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(PATH, token, Map.of("query", "x", "topK", 0))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(PATH, token, Map.of("query", "x", "topK", 51))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresAuth() {
        post(PATH, null, Map.of("query", "x")).assertHttpStatus(401);
    }

    // ------------------------------------------------------------ 辅助

    private Long createKb(String name, Long ownerId, int isSystem) {
        long id = nextTestId();
        jdbc.update("INSERT INTO knowledge_base (id, user_id, name, status, is_system, deleted,"
                        + " document_count, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?, 0, 0, NOW(), NOW())",
                id, ownerId == null ? 0L : ownerId, name, isSystem);
        return id;
    }

    private Long createDoc(Long kbId, Long ownerId, String fileName) {
        long id = nextTestId();
        jdbc.update("INSERT INTO document (id, user_id, knowledge_base_id, file_name, file_type,"
                        + " file_size, parse_status, chunk_status, vector_status, deleted, vector_purged,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'md', 0, 'SUCCESS', 'SUCCESS', 'SUCCESS', 0, 0, NOW(), NOW())",
                id, ownerId, kbId, fileName);
        return id;
    }

    private Long createChunk(Long docId, Long kbId, Long ownerId, int index, String content) {
        long id = nextTestId();
        jdbc.update("INSERT INTO document_chunk (id, user_id, knowledge_base_id, document_id,"
                        + " chunk_index, content, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, NOW())",
                id, ownerId, kbId, docId, index, content);
        return id;
    }

    /** 让一个库变成「可搜」——必须有 vector_status=SUCCESS 的文档，否则不会被纳入检索范围 */
    private void makeSearchable(long kbId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE knowledge_base_id = ?",
                Integer.class, kbId) == 0) {
            createDoc(kbId, myUserId, "d.md");
        }
    }

    private void stubSearch(AiServiceClient.Source... sources) {
        stubSearch(true, sources);
    }

    private void stubSearch(boolean reranked, AiServiceClient.Source... sources) {
        when(aiServiceClient.search(any())).thenReturn(new AiServiceClient.SearchData(
                List.of(sources), sources.length, reranked));
    }

    /** 跑一次检索并把 AI 客户端实际收到的「逗号分隔知识库 id」取出来断言 */
    private String capturedKbIds() {
        return capturedKbIds(null);
    }

    private String capturedKbIds(String explicitKbId) {
        Map<String, Object> body = explicitKbId == null
                ? Map.of("query", "x")
                : Map.of("query", "x", "knowledgeBaseId", explicitKbId);
        post(PATH, token, body).assertSuccess();

        ArgumentCaptor<AiServiceClient.SearchRequest> captor =
                ArgumentCaptor.forClass(AiServiceClient.SearchRequest.class);
        verify(aiServiceClient).search(captor.capture());
        return captor.getValue().knowledgeBaseId();
    }

    /** 测试用小整数 id —— 测试库每轮会清表，不会和雪花 id 撞 */
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(900_000L);

    private long nextTestId() {
        return SEQ.incrementAndGet();
    }
}
