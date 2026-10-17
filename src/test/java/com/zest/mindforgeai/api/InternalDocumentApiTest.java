package com.zest.mindforgeai.api;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.properties.InternalApiProperties;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部文档接口：{@code /api/internal/documents/**}（只给 Python AI 服务调）。
 *
 * <p>认证不走 JWT —— 调用方是服务不是用户，用 {@code X-Service-Token} 共享密钥。
 * 这些接口<b>不做归属校验</b>：没有「当前登录用户」这个概念，
 * 安全性由「服务令牌 + 网络隔离」保证，所以用例里也不该期待 404 越权语义。
 *
 * <p>★ 三条容易回归的语义：
 * <ol>
 *   <li><b>认领即占用</b>：claim 把 PENDING 改成 PARSING，第二个实例不会再拿到；</li>
 *   <li><b>回写是局部的</b>：{@code null} 表示「这一阶段还没到，别动它」，
 *       只有 {@code errorMessage} 例外（空字符串 = 明确清空）；</li>
 *   <li><b>分块写入幂等</b>：先删后插，重复上报不会撞唯一键。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-17
 */
@DisplayName("内部接口 - 文档处理")
class InternalDocumentApiTest extends AbstractApiTest {

    private static final String BASE = "/internal/documents";

    @Autowired
    private InternalApiProperties internalApiProperties;

    // ------------------------------------------------------------ 认领

    @Test
    @DisplayName("★ 认领：PENDING 文档被改成 PARSING，并给出预签名下载地址")
    void claimMarksParsingAndReturnsDownloadUrl() {
        long docId = insertDocument("后端简历.pdf", "PENDING", 0, 0);

        ApiResult res = call(HttpMethod.POST, BASE + "/claim?limit=10", null, serviceToken())
                .assertSuccess().assertCamelCase();

        JsonNode records = res.data();
        assertThat(records.size()).isEqualTo(1);

        JsonNode claim = records.get(0);
        assertThat(claim.path("documentId").asText()).isEqualTo(String.valueOf(docId));
        assertThat(claim.path("fileName").asText()).isEqualTo("后端简历.pdf");
        assertThat(claim.path("userId").asText()).isEqualTo(String.valueOf(userId(USER_NAME)));
        assertThat(claim.path("downloadUrl").asText())
                .as("下载地址是预签名 URL，不暴露 OSS 配置")
                .isEqualTo("https://test.invalid/mock-object");

        assertThat(dbParseStatus(docId)).isEqualTo("PARSING");
    }

    @Test
    @DisplayName("★ 认领是排他的：同一篇文档不会被认领两次")
    void claimIsExclusive() {
        insertDocument("a.pdf", "PENDING", 0, 0);

        assertThat(call(HttpMethod.POST, BASE + "/claim", null, serviceToken())
                .assertSuccess().data().size()).isEqualTo(1);

        assertThat(call(HttpMethod.POST, BASE + "/claim", null, serviceToken())
                .assertSuccess().data().size()).as("已被改成 PARSING，不该再出现").isZero();
    }

    @Test
    @DisplayName("认领：limit 生效，且只取 PENDING（SUCCESS 的不动）")
    void claimRespectsLimitAndStatus() {
        insertDocument("a.pdf", "PENDING", 0, 0);
        insertDocument("b.pdf", "PENDING", 0, 0);
        insertDocument("c.pdf", "SUCCESS", 0, 0);

        assertThat(call(HttpMethod.POST, BASE + "/claim?limit=1", null, serviceToken())
                .assertSuccess().data().size()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 状态回写

    @Test
    @DisplayName("★ 回写：只改传了的阶段，没传的保持原状")
    void updateStatusIsPartial() {
        long docId = insertDocument("a.pdf", "PARSING", 0, 0);

        call(HttpMethod.PUT, BASE + "/" + docId + "/status",
                Map.of("parseStatus", "SUCCESS"), serviceToken()).assertSuccess();

        Map<String, Object> row = documentRow(docId);
        assertThat(row.get("parse_status")).isEqualTo("SUCCESS");
        assertThat(row.get("chunk_status")).as("没传就不该动").isEqualTo("PENDING");
        assertThat(row.get("vector_status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("★ 回写：errorMessage 传空串是「清空」，传 null 才是「不改」")
    void updateStatusClearsErrorMessage() {
        long docId = insertDocument("a.pdf", "FAILED", 0, 0);
        jdbc.update("UPDATE document SET error_message = ? WHERE id = ?", "上次解析炸了", docId);

        call(HttpMethod.PUT, BASE + "/" + docId + "/status",
                Map.of("errorMessage", ""), serviceToken()).assertSuccess();
        assertThat(documentRow(docId).get("error_message")).as("空串表示明确清空").isEqualTo("");

        call(HttpMethod.PUT, BASE + "/" + docId + "/status",
                Map.of("parseStatus", "FAILED", "errorMessage", "页数超限"), serviceToken())
                .assertSuccess();
        assertThat(documentRow(docId).get("error_message")).isEqualTo("页数超限");
    }

    @Test
    @DisplayName("回写：文档不存在 → 40404")
    void updateStatusUnknownDocument() {
        call(HttpMethod.PUT, BASE + "/999999999/status",
                Map.of("parseStatus", "SUCCESS"), serviceToken())
                .assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 分块写入

    @Test
    @DisplayName("★ 写分块：批量插入，userId / knowledgeBaseId 从文档继承")
    void saveChunksInsertsRows() {
        long docId = insertDocument("a.pdf", "SUCCESS", 0, 0);

        ApiResult res = call(HttpMethod.POST, BASE + "/" + docId + "/chunks", Map.of(
                "chunks", List.of(
                        Map.of("chunkIndex", 0, "content", "第一段", "tokenCount", 12,
                                "pageNo", 1, "vectorId", "v-0"),
                        Map.of("chunkIndex", 1, "content", "第二段", "tokenCount", 9))),
                serviceToken()).assertSuccess();

        assertThat(res.data().asInt()).isEqualTo(2);
        assertThat(chunkCount(docId)).isEqualTo(2);

        Map<String, Object> first = jdbc.queryForMap(
                "SELECT user_id, knowledge_base_id, content FROM document_chunk "
                        + "WHERE document_id = ? AND chunk_index = 0", docId);
        assertThat(((Number) first.get("user_id")).longValue()).isEqualTo(userId(USER_NAME));
        assertThat(((Number) first.get("knowledge_base_id")).longValue()).isEqualTo(1L);
        assertThat(first.get("content")).isEqualTo("第一段");
    }

    @Test
    @DisplayName("★ 写分块是幂等的：重复上报先删后插，不会撞 uk_doc_chunk_index")
    void saveChunksIsIdempotent() {
        long docId = insertDocument("a.pdf", "SUCCESS", 0, 0);
        Map<String, Object> twoChunks = Map.of("chunks", List.of(
                Map.of("chunkIndex", 0, "content", "旧一"),
                Map.of("chunkIndex", 1, "content", "旧二")));

        call(HttpMethod.POST, BASE + "/" + docId + "/chunks", twoChunks, serviceToken())
                .assertSuccess();
        assertThat(chunkCount(docId)).isEqualTo(2);

        ApiResult again = call(HttpMethod.POST, BASE + "/" + docId + "/chunks",
                Map.of("chunks", List.of(Map.of("chunkIndex", 0, "content", "新一"))),
                serviceToken()).assertSuccess();

        assertThat(again.data().asInt()).isEqualTo(1);
        assertThat(chunkCount(docId)).as("旧分块应被清掉").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT content FROM document_chunk WHERE document_id = ?", String.class, docId))
                .isEqualTo("新一");
    }

    @Test
    @DisplayName("写分块：空列表 → 40001（@NotEmpty）")
    void saveChunksRejectsEmptyList() {
        long docId = insertDocument("a.pdf", "SUCCESS", 0, 0);
        call(HttpMethod.POST, BASE + "/" + docId + "/chunks",
                Map.of("chunks", List.of()), serviceToken())
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("写分块：文档不存在 → 40404")
    void saveChunksUnknownDocument() {
        call(HttpMethod.POST, BASE + "/999999999/chunks",
                Map.of("chunks", List.of(Map.of("chunkIndex", 0, "content", "x"))), serviceToken())
                .assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 向量清理

    @Test
    @DisplayName("★ 待清理向量：只列「已逻辑删除且未清理」的文档")
    void pendingVectorPurgeListsDeletedOnly() {
        long deleted = insertDocument("deleted.pdf", "SUCCESS", 1, 0);
        insertDocument("live.pdf", "SUCCESS", 0, 0);

        ApiResult res = call(HttpMethod.GET, BASE + "/pending-vector-purge", null, serviceToken())
                .assertSuccess().assertCamelCase();

        assertThat(res.data().size()).isEqualTo(1);
        assertThat(res.data().get(0).path("documentId").asText())
                .isEqualTo(String.valueOf(deleted));
        assertThat(res.data().get(0).path("fileName").asText()).isEqualTo("deleted.pdf");
    }

    @Test
    @DisplayName("★ 标记已清理后不再出现在待清理列表里")
    void markVectorPurgedRemovesFromPendingList() {
        long docId = insertDocument("deleted.pdf", "SUCCESS", 1, 0);

        call(HttpMethod.PUT, BASE + "/" + docId + "/vector-purged", null, serviceToken())
                .assertSuccess();

        assertThat(vectorPurged(docId)).isEqualTo(1);
        assertThat(call(HttpMethod.GET, BASE + "/pending-vector-purge", null, serviceToken())
                .assertSuccess().data().size()).isZero();
    }

    @Test
    @DisplayName("标记已清理是幂等的：重复调用不报错")
    void markVectorPurgedIsIdempotent() {
        long docId = insertDocument("deleted.pdf", "SUCCESS", 1, 0);

        call(HttpMethod.PUT, BASE + "/" + docId + "/vector-purged", null, serviceToken())
                .assertSuccess();
        call(HttpMethod.PUT, BASE + "/" + docId + "/vector-purged", null, serviceToken())
                .assertSuccess();
    }

    @Test
    @DisplayName("★ 没被删除的文档不会被标记（SQL 里带 deleted = 1）")
    void markVectorPurgedIgnoresLiveDocument() {
        long docId = insertDocument("live.pdf", "SUCCESS", 0, 0);

        call(HttpMethod.PUT, BASE + "/" + docId + "/vector-purged", null, serviceToken())
                .assertSuccess();

        assertThat(vectorPurged(docId)).as("未删除的文档不该被标记").isZero();
    }

    // ------------------------------------------------------------ 认证

    @Test
    @DisplayName("★ 没有服务令牌 → 401")
    void rejectsMissingServiceToken() {
        call(HttpMethod.GET, BASE + "/pending-vector-purge", null, null)
                .assertHttpStatus(401)
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("★ 服务令牌错误 → 401")
    void rejectsWrongServiceToken() {
        call(HttpMethod.POST, BASE + "/claim", null, "not-the-real-token")
                .assertHttpStatus(401)
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    // ------------------------------------------------------------ 辅助

    private String serviceToken() {
        return internalApiProperties.getServiceToken();
    }

    /** 内部接口不走 JWT，只能自己拼 X-Service-Token 头。 */
    private ApiResult call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Service-Token", token);
        }
        ResponseEntity<JsonNode> response = rest.exchange(
                path, method, new HttpEntity<>(body, headers), JsonNode.class);
        return new ApiResult(response.getStatusCode().value(), response.getBody());
    }

    /**
     * 直接插一条文档 —— 内部接口不校验归属，不需要真的建知识库。
     * 但 {@code user_id / knowledge_base_id / file_name / file_size} 都是 NOT NULL，得给齐。
     */
    private long insertDocument(String fileName, String parseStatus, int deleted, int vectorPurged) {
        long id = IdWorker.getId();
        jdbc.update("""
                INSERT INTO document (id, user_id, knowledge_base_id, file_name, file_type,
                                      file_url, file_size, parse_status, chunk_status, vector_status,
                                      deleted, vector_purged, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PDF', ?, 1024, ?, 'PENDING', 'PENDING', ?, ?, NOW(), NOW())
                """, id, userId(USER_NAME), 1L, fileName, "kb/" + id + ".pdf",
                parseStatus, deleted, vectorPurged);
        return id;
    }

    private Map<String, Object> documentRow(long docId) {
        return jdbc.queryForMap(
                "SELECT parse_status, chunk_status, vector_status, error_message "
                        + "FROM document WHERE id = ?", docId);
    }

    private String dbParseStatus(long docId) {
        return jdbc.queryForObject(
                "SELECT parse_status FROM document WHERE id = ?", String.class, docId);
    }

    private int chunkCount(long docId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_chunk WHERE document_id = ?", Integer.class, docId);
    }

    private int vectorPurged(long docId) {
        return jdbc.queryForObject(
                "SELECT vector_purged FROM document WHERE id = ?", Integer.class, docId);
    }
}
