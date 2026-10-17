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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户端会话（CRUD + 同步发消息）。
 *
 * <p>这一组接口此前只有 SSE 的错误路径被测过（{@link SseErrorResponseTest}），
 * 真正的主链路 —— 建会话、列会话、看详情、发消息落库、删除级联 —— 一条都没覆盖。
 * 重点三块：
 * <ul>
 *   <li><b>消息落库</b> —— 同步接口调的是被 mock 的 AI，但「一问一答两条消息 +
 *       引用」必须真的写进库，否则前端刷新后历史是空的；</li>
 *   <li><b>归属校验</b> —— 拿别人的会话 id 读写都报「不存在」（40406），不报 403；</li>
 *   <li><b>删除级联</b> —— 没有外键，chat_message / chat_source 得手工清干净。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-16
 */
@DisplayName("会话管理")
class ConversationApiTest extends AbstractApiTest {

    private static final String PATH = "/app/conversations";

    private String token;
    private Long myUserId;

    @BeforeEach
    void setUp() {
        token = loginAsUser();
        myUserId = userId(USER_NAME);
    }

    // ------------------------------------------------------------ 新建

    @Test
    @DisplayName("新建会话：标题回显，初始消息数为 0")
    void createReturnsTitle() {
        ApiResult res = post(PATH, token, Map.of("title", "Spring 事务怎么学")).assertSuccess();

        assertThat(res.str("title")).isEqualTo("Spring 事务怎么学");
        assertThat(res.data().path("messageCount").asLong()).isZero();
        // 没传 knowledgeBaseId 就是通用会话，回 null 而不是 "" 或 "null"
        assertThat(res.data().path("knowledgeBaseId").isNull()).isTrue();
        assertThat(res.traceId()).isNotBlank();

        // 真的落库了，且归属是当前用户
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE user_id = ? AND title = ?",
                Integer.class, myUserId, "Spring 事务怎么学");
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("关联自己的知识库 → 可用；关联别人的 → 40403")
    void knowledgeBaseIdIsValidated() {
        long mine = createKb("我的题库", myUserId, 0);
        ApiResult res = post(PATH, token,
                        Map.of("title", "带着库聊", "knowledgeBaseId", String.valueOf(mine)))
                .assertSuccess();
        assertThat(res.str("knowledgeBaseId")).isEqualTo(String.valueOf(mine));

        Long others = createAccount("conv_other");
        long notMine = createKb("别人的库", others, 0);
        post(PATH, token, Map.of("title", "x", "knowledgeBaseId", String.valueOf(notMine)))
                .assertCode(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("knowledgeBaseId 传 \"0\" / \"null\" 当成「不关联」，不报错")
    void sentinelKnowledgeBaseIdsMeanNone() {
        // 前端未选择知识库时发这两个值之一，不能当成非法 id 把用户的提问挡掉
        for (String sentinel : List.of("0", "null")) {
            assertThat(post(PATH, token, Map.of("title", "x", "knowledgeBaseId", sentinel))
                    .assertSuccess().data().path("knowledgeBaseId").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("knowledgeBaseId 不是数字 / 为负 → 40001")
    void malformedKnowledgeBaseIdIsRejected() {
        post(PATH, token, Map.of("title", "x", "knowledgeBaseId", "not-a-number"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(PATH, token, Map.of("title", "x", "knowledgeBaseId", "-1"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("标题为空 / 超长 → 40001")
    void titleIsValidated() {
        post(PATH, token, Map.of("title", "   "))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(PATH, token, Map.of("title", "x".repeat(256)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresAuth() {
        post(PATH, null, Map.of("title", "x")).assertHttpStatus(401);
        get(PATH, null).assertHttpStatus(401);
    }

    // ------------------------------------------------------------ 列表

    @Test
    @DisplayName("列表：分页结构 + 按最后活跃时间倒序")
    void pageReturnsRecordsSortedByRecentActivity() {
        long old = create("老会话");
        long recent = create("新会话");

        // 老会话往前挪两天 —— 模拟「很久没聊过」
        jdbc.update("UPDATE conversation SET updated_at = NOW() - INTERVAL 2 DAY WHERE id = ?", old);
        jdbc.update("UPDATE conversation SET updated_at = NOW() WHERE id = ?", recent);

        var data = get(PATH, token).assertSuccess().data();
        assertThat(data.path("total").asLong()).isEqualTo(2);
        assertThat(data.path("page").asInt()).isEqualTo(1);

        var titles = data.path("records").findValuesAsText("title");
        assertThat(titles).containsExactly("新会话", "老会话");
    }

    @Test
    @DisplayName("别人的会话不出现在我的列表里")
    void pageIsScopedToUser() {
        create("我的会话");

        Long otherUserId = createAccount("conv_other2");
        create("别人的会话", otherUserId);

        var records = get(PATH, token).assertSuccess().data().path("records");
        assertThat(records.findValuesAsText("title")).containsExactly("我的会话");
    }

    @Test
    @DisplayName("sortBy 不在白名单 → 40001（排序是拼 SQL 的，不能透传字段名）")
    void sortByIsWhitelisted() {
        create("x");
        get(PATH + "?sortBy=user_id&sortOrder=desc", token)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("pageSize 超大 → 被压到 100，不会把全表拉回来")
    void pageSizeIsCapped() {
        for (int i = 0; i < 3; i++) {
            create("会话" + i);
        }
        ApiResult res = get(PATH + "?page=1&pageSize=5000", token).assertSuccess();
        assertThat(res.data().path("size").asInt()).isLessThanOrEqualTo(100);
    }

    // ------------------------------------------------------------ 详情

    @Test
    @DisplayName("详情：含消息列表，问在前答在后")
    void detailReturnsMessagesInOrder() {
        long id = create("带历史的会话");
        insertMessage(id, myUserId, "USER", "Java 线程池有哪几种？");
        insertMessage(id, myUserId, "ASSISTANT", "常见的有 4 种……");

        var data = get(PATH + "/" + id, token).assertSuccess().data();
        assertThat(data.path("title").asText()).isEqualTo("带历史的会话");
        assertThat(data.path("messageCount").asInt()).isEqualTo(2);

        var messages = data.path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("USER");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("ASSISTANT");
    }

    @Test
    @DisplayName("会话不存在 / 是别人的 → 40406（不暴露 id 是否存在）")
    void detailOfOthersIsNotFound() {
        Long otherUserId = createAccount("conv_other3");
        long theirs = create("别人的", otherUserId);

        get(PATH + "/999999", token).assertCode(ErrorCode.CONVERSATION_NOT_FOUND.getCode());
        get(PATH + "/" + theirs, token).assertCode(ErrorCode.CONVERSATION_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 发消息

    @Test
    @DisplayName("★ 发消息：一问一答两条真的落库，回答原样带回")
    void sendMessagePersistsBothSides() {
        long id = create("提问的会话");
        stubChat();

        ApiResult res = post(PATH + "/" + id + "/messages", token,
                        Map.of("message", "MySQL 索引什么时候会失效？"))
                .assertSuccess();

        assertThat(res.str("answer")).isEqualTo("索引失效通常是这三种情况……");
        assertThat(res.data().path("hasContext").asBoolean()).isTrue();
        assertThat(res.data().path("hasAnswer").asBoolean()).isTrue();
        assertThat(res.str("messageId")).isNotBlank();

        // 关键是落库：用户消息 + 助手消息各一条，内容都要对
        List<String> rows = jdbc.query(
                "SELECT role, content FROM chat_message WHERE conversation_id = ? ORDER BY id",
                (rs, n) -> rs.getString("role") + "|" + rs.getString("content"), id);
        assertThat(rows).containsExactly(
                "USER|MySQL 索引什么时候会失效？",
                "ASSISTANT|索引失效通常是这三种情况……");

        // 会话的 updated_at 被刷新 —— 列表是按「最后活跃」排的
        assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation WHERE id = ?",
                java.time.LocalDateTime.class, id)).isNotNull();
    }

    @Test
    @DisplayName("引用来源跟着助手消息一起落库，详情里能取回")
    void sendMessagePersistsSources() {
        long id = create("提问的会话");
        long kbId = createKb("参考资料库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "索引优化.md");
        long chunkId = createChunk(docId, kbId, myUserId, 0, "MySQL 索引失效的三种典型场景");
        stubChat(List.of(new AiServiceClient.Source(
                String.valueOf(chunkId), String.valueOf(docId), String.valueOf(kbId),
                0, "MySQL 索引失效的三种典型场景", 0.91)));

        ApiResult res = post(PATH + "/" + id + "/messages", token,
                        Map.of("message", "索引")).assertSuccess();

        // 引用带的是「文档名 + 分块 id + 得分」—— 正文不带，
        // 前端拿 chunkId 去 GET /app/chunks/{id} 取（见 SourceVO 的注释）
        var first = res.data().path("sources").get(0);
        assertThat(first.path("documentName").asText()).isEqualTo("索引优化.md");
        assertThat(first.path("chunkId").asLong()).isEqualTo(chunkId);
        assertThat(first.path("score").asDouble()).isEqualTo(0.91);

        String messageId = res.str("messageId");
        Integer sources = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_source WHERE message_id = ?", Integer.class,
                Long.valueOf(messageId));
        assertThat(sources).isEqualTo(1);

        var detail = get(PATH + "/" + id, token).assertSuccess().data();
        var assistant = detail.path("messages").get(1);
        assertThat(assistant.path("sources")).hasSize(1);
        assertThat(assistant.path("sources").get(0).path("documentName").asText())
                .isEqualTo("索引优化.md");
    }

    @Test
    @DisplayName("★ chunkId 存的是 document_chunk.id（不是向量库的 vector_id）——"
            + "否则前端拿它取原文会 404")
    void chunkIdMapsToRealChunkRow() {
        long id = create("提问的会话");
        long kbId = createKb("参考资料库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "a.md");
        long chunkId = createChunk(docId, kbId, myUserId, 2, "正文二");
        // 同一个文档再造一个别的分块，确认是按 chunkIndex 定位而不是「取第一个」
        createChunk(docId, kbId, myUserId, 0, "正文零");
        createChunk(docId, kbId, myUserId, 1, "正文一");

        stubChat(List.of(new AiServiceClient.Source(
                "doc" + docId + "-chunk2", String.valueOf(docId), String.valueOf(kbId),
                2, "正文二", 0.8)));

        var sources = post(PATH + "/" + id + "/messages", token, Map.of("message", "x"))
                .assertSuccess().data().path("sources");

        assertThat(sources.get(0).path("chunkId").asLong()).isEqualTo(chunkId);
        // 取原文的接口也真的能查到这块
        get("/app/chunks/" + chunkId, token).assertSuccess();
    }

    @Test
    @DisplayName("topK / rerank / 风格 id 原样透传给 AI 服务")
    void requestOptionsArePassedThrough() {
        long id = create("提问的会话");
        stubChat();

        post(PATH + "/" + id + "/messages", token, Map.of(
                "message", "x",
                "topK", 20,
                "enableRerank", true,
                "promptId", "12345")).assertSuccess();

        ArgumentCaptor<AiServiceClient.ChatRequest> captor =
                ArgumentCaptor.forClass(AiServiceClient.ChatRequest.class);
        verify(aiServiceClient).chat(captor.capture());
        AiServiceClient.ChatRequest sent = captor.getValue();

        assertThat(sent.query()).isEqualTo("x");
        assertThat(sent.topK()).isEqualTo(20);
        assertThat(sent.enableRerank()).isTrue();
        assertThat(sent.promptId()).isEqualTo("12345");
        // userId / conversationId 也必须是对的那两个 —— 串了会导致 Python 侧记账记到别人头上
        assertThat(sent.userId()).isEqualTo(String.valueOf(myUserId));
        assertThat(sent.conversationId()).isEqualTo(String.valueOf(id));
    }

    @Test
    @DisplayName("第二轮提问会带上历史（一问一答算一轮）")
    void historyIsSentOnSecondTurn() {
        long id = create("多轮会话");
        stubChat();

        post(PATH + "/" + id + "/messages", token, Map.of("message", "第一问")).assertSuccess();
        post(PATH + "/" + id + "/messages", token, Map.of("message", "第二问")).assertSuccess();

        ArgumentCaptor<AiServiceClient.ChatRequest> captor =
                ArgumentCaptor.forClass(AiServiceClient.ChatRequest.class);
        verify(aiServiceClient, org.mockito.Mockito.times(2)).chat(captor.capture());

        // 第一次带上刚存的用户问题（loadHistory 在 saveMessage 之后跑，上下文才完整），
        // 第二次就是完整的一问一答
        assertThat(captor.getAllValues().get(0).history())
                .extracting(h -> h.role() + ":" + h.content())
                .containsExactly("user:第一问");
        assertThat(captor.getAllValues().get(1).history())
                .extracting(h -> h.role() + ":" + h.content())
                .containsExactly("user:第一问", "assistant:索引失效通常是这三种情况……", "user:第二问");
    }

    @Test
    @DisplayName("消息为空 / topK 越界 → 40001，且【不调 AI】（别白花一次钱）")
    void messageIsValidatedBeforeCallingAi() {
        long id = create("提问的会话");

        post(PATH + "/" + id + "/messages", token, Map.of("message", "  "))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(PATH + "/" + id + "/messages", token, Map.of("message", "x", "topK", 0))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
        post(PATH + "/" + id + "/messages", token, Map.of("message", "x", "topK", 51))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        verify(aiServiceClient, org.mockito.Mockito.never()).chat(any());
    }

    @Test
    @DisplayName("给不存在的会话发消息 → 40406")
    void sendToMissingConversationIsNotFound() {
        post(PATH + "/999999/messages", token, Map.of("message", "x"))
                .assertCode(ErrorCode.CONVERSATION_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("给别人的会话发消息 → 40406")
    void sendToOthersConversationIsNotFound() {
        Long otherUserId = createAccount("conv_other4");
        long theirs = create("别人的", otherUserId);

        post(PATH + "/" + theirs + "/messages", token, Map.of("message", "套话"))
                .assertCode(ErrorCode.CONVERSATION_NOT_FOUND.getCode());

        // 消息也不该落到别人会话里
        Integer leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?", Integer.class, theirs);
        assertThat(leaked).isZero();
    }

    // ------------------------------------------------------------ 删除

    @Test
    @DisplayName("删除会话：详情查不到，列表也少了")
    void deleteRemovesConversation() {
        long id = create("要删的会话");
        insertMessage(id, myUserId, "USER", "问");
        insertMessage(id, myUserId, "ASSISTANT", "答");

        delete(PATH + "/" + id, token).assertSuccess();

        get(PATH + "/" + id, token).assertCode(ErrorCode.CONVERSATION_NOT_FOUND.getCode());
        assertThat(get(PATH, token).assertSuccess().data().path("records")).isEmpty();
    }

    @Test
    @DisplayName("★ 删除会话级联清掉消息与引用（没有外键，必须手工清）")
    void deleteCascadesMessagesAndSources() {
        long id = create("要删的会话");
        long kbId = createKb("参考资料库", myUserId, 0);
        long docId = createDoc(kbId, myUserId, "a.md");
        long chunkId = createChunk(docId, kbId, myUserId, 0, "正文");
        long userMsg = insertMessage(id, myUserId, "USER", "问");
        stubChat();
        String assistantMsg = post(PATH + "/" + id + "/messages", token,
                        Map.of("message", "索引")).assertSuccess().str("messageId");

        jdbc.update("INSERT INTO chat_source (id, message_id, chunk_id, document_id,"
                        + " page_no, relevance_score, source_title, created_at)"
                        + " VALUES (?, ?, ?, ?, 0, 0.9, ?, NOW())",
                nextTestId(), Long.valueOf(assistantMsg), chunkId, docId, "索引优化.md");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_source WHERE message_id = ?",
                Integer.class, Long.valueOf(assistantMsg))).isEqualTo(1);

        delete(PATH + "/" + id, token).assertSuccess();

        // 消息物理删、引用物理删、会话逻辑删（deleted 置 1，行还在）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_source WHERE message_id IN (?, ?)",
                Integer.class, userMsg, Long.valueOf(assistantMsg))).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM conversation WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("删别人的会话 → 40406，对方的数据还在")
    void cannotDeleteOthersConversation() {
        Long otherUserId = createAccount("conv_other5");
        long theirs = create("别人的", otherUserId);
        insertMessage(theirs, otherUserId, "USER", "问");

        delete(PATH + "/" + theirs, token)
                .assertCode(ErrorCode.CONVERSATION_NOT_FOUND.getCode());

        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM conversation WHERE id = ?", Integer.class, theirs)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?",
                Integer.class, theirs)).isEqualTo(1);
    }

    // ------------------------------------------------------------ 辅助

    private long create(String title) {
        return create(title, myUserId);
    }

    private long create(String title, Long userId) {
        long id = nextTestId();
        jdbc.update("INSERT INTO conversation (id, user_id, title, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 1, NOW(), NOW())", id, userId, title);
        return id;
    }

    private long insertMessage(long conversationId, Long userId, String role, String content) {
        long id = nextTestId();
        jdbc.update("INSERT INTO chat_message (id, conversation_id, user_id, role, content,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, NOW())",
                id, conversationId, userId, role, content);
        return id;
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
                        + " chunk_index, content, created_at) VALUES (?, ?, ?, ?, ?, ?, NOW())",
                id, ownerId, kbId, docId, index, content);
        return id;
    }

    /** 无引用的回答 */
    private void stubChat() {
        stubChat(List.of());
    }

    private void stubChat(List<AiServiceClient.Source> sources) {
        when(aiServiceClient.chat(any())).thenReturn(new AiServiceClient.ChatData(
                "索引失效通常是这三种情况……", sources, true, true, false));
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(900_000L);

    private long nextTestId() {
        return SEQ.incrementAndGet();
    }
}
