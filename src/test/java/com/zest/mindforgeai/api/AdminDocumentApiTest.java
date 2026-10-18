package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端文档管理：{@code /admin/documents/**} 与 {@code /admin/knowledge-bases/{id}/documents/**}。
 *
 * <p>此前只有「列表 + 看分块」被 {@link PageSortApiTest} 顺带覆盖（还是为了测排序），
 * 上传 / 详情 / 改状态 / 重试 / 删除 / 两个批量接口都没人管。
 *
 * <p>★ 这层和用户端文档接口的关键差别：<b>管理端能跨用户操作</b>，
 * 所以没有归属校验 —— 安全性完全靠 {@code admin:document:*} 权限码。
 * 用例因此重点守三件事：
 * <ol>
 *   <li><b>软删不删 OSS</b>：文档与 sys_file 都是逻辑删除，留恢复窗口；</li>
 *   <li><b>只有失败才能重试</b>：允许重试「处理中」的文档会让 AI 对同一份文档跑两遍；</li>
 *   <li><b>改状态要校验取值</b>：状态是流水线的契约，随便写个字符串 Python 侧就认不出来。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-18
 */
@DisplayName("管理端-文档管理")
class AdminDocumentApiTest extends AbstractApiTest {

    private String adminToken;

    @BeforeEach
    void loginAdmin() {
        adminToken = loginAsAdmin();
    }

    // ------------------------------------------------------------ 上传

    @Test
    @DisplayName("★ 上传：落库为 PENDING 三连，归属跟着知识库走")
    void uploadCreatesPendingDocument() {
        long kbId = createKb("官方面试题库");

        ApiResult res = uploadDoc("java-concurrency.md", kbId).assertSuccess().assertCamelCase();

        assertThat(res.str("documentId")).isNotBlank();
        assertThat(res.str("fileName")).isEqualTo("java-concurrency.md");
        assertThat(res.str("fileType")).isEqualTo("MD");
        assertThat(res.str("knowledgeBaseId")).isEqualTo(String.valueOf(kbId));
        assertThat(res.str("parseStatus")).isEqualTo("PENDING");
        assertThat(res.str("chunkStatus")).isEqualTo("PENDING");
        assertThat(res.str("vectorStatus")).isEqualTo("PENDING");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, knowledge_base_id, deleted FROM document WHERE id = ?",
                docId(res));
        assertThat(((Number) row.get("user_id")).longValue())
                .as("文档归属应继承知识库的 owner")
                .isEqualTo(userId(ADMIN_NAME));
        assertThat(((Number) row.get("deleted")).intValue()).isZero();
    }

    @Test
    @DisplayName("上传：不支持的文件类型 → 42201")
    void uploadRejectsUnsupportedType() {
        long kbId = createKb("类型校验库");

        upload("/admin/knowledge-bases/" + kbId + "/documents/upload", adminToken, "file",
                "virus.exe", new byte[]{1, 2, 3}, "application/octet-stream")
                .assertCode(ErrorCode.FILE_TYPE_UNSUPPORTED.getCode());
    }

    @Test
    @DisplayName("上传：知识库不存在 → 40403")
    void uploadToUnknownKnowledgeBase() {
        upload("/admin/knowledge-bases/999999999/documents/upload", adminToken, "file",
                "a.txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain")
                .assertCode(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 列表 / 详情

    @Test
    @DisplayName("★ 列表：按知识库过滤，关键词只搜文件名")
    void listFiltersByKnowledgeBaseAndKeyword() {
        long kbA = createKb("库 A");
        long kbB = createKb("库 B");
        uploadDoc("redis-cache.md", kbA).assertSuccess();
        uploadDoc("mysql-index.md", kbB).assertSuccess();

        ApiResult byKb = get("/admin/documents?knowledgeBaseId=" + kbA, adminToken).assertSuccess();
        assertThat(byKb.data().path("total").asLong()).isEqualTo(1);
        assertThat(byKb.data().path("records").get(0).path("fileName").asText())
                .isEqualTo("redis-cache.md");

        ApiResult byKeyword = get("/admin/documents?keyword=mysql", adminToken).assertSuccess();
        assertThat(byKeyword.data().path("total").asLong()).isEqualTo(1);
        assertThat(byKeyword.data().path("records").get(0).path("fileName").asText())
                .isEqualTo("mysql-index.md");
    }

    @Test
    @DisplayName("详情：带上知识库名与 owner 用户名（管理端要能定位到人）")
    void detailReturnsOwnerInfo() {
        long kbId = createKb("详情库");
        long id = docId(uploadDoc("detail.txt", kbId));

        ApiResult res = get("/admin/documents/" + id, adminToken)
                .assertSuccess().assertCamelCase();

        assertThat(res.str("documentId")).isEqualTo(String.valueOf(id));
        assertThat(res.str("knowledgeBaseName")).isEqualTo("详情库");
        assertThat(res.str("username")).isEqualTo(ADMIN_NAME);
    }

    @Test
    @DisplayName("详情：不存在的 id → 40404")
    void detailUnknownDocument() {
        get("/admin/documents/999999999", adminToken)
                .assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 改状态

    @Test
    @DisplayName("★ 改状态：写入成功，且非法取值被拦（状态是流水线的契约）")
    void updateStatusValidatesAndWrites() {
        long id = docId(uploadDoc("status.txt", createKb("状态库")));

        put("/admin/documents/" + id + "/status", adminToken,
                Map.of("parseStatus", "SUCCESS", "pageCount", 3)).assertSuccess();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT parse_status, page_count FROM document WHERE id = ?", id);
        assertThat(row.get("parse_status")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("page_count")).intValue()).isEqualTo(3);

        put("/admin/documents/" + id + "/status", adminToken, Map.of("parseStatus", "DONE"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("改状态：一个字段都不传 → 40001（静默 200 会让管理员以为改成功了）")
    void updateStatusRejectsEmptyPayload() {
        long id = docId(uploadDoc("empty.txt", createKb("空操作库")));

        put("/admin/documents/" + id + "/status", adminToken, Map.of())
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 重试

    @Test
    @DisplayName("★ 重试：失败文档重置为 PENDING，并清掉上次的错误信息")
    void retryResetsFailedDocument() {
        long id = docId(uploadDoc("retry.txt", createKb("重试库")));

        put("/admin/documents/" + id + "/status", adminToken,
                Map.of("parseStatus", "FAILED", "errorMessage", "PDF 加密无法解析"))
                .assertSuccess();

        post("/admin/documents/" + id + "/retry", adminToken, null).assertSuccess();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT parse_status, chunk_status, vector_status, error_message "
                        + "FROM document WHERE id = ?", id);
        assertThat(row.get("parse_status")).isEqualTo("PENDING");
        assertThat(row.get("chunk_status")).isEqualTo("PENDING");
        assertThat(row.get("vector_status")).isEqualTo("PENDING");
        assertThat(row.get("error_message"))
                .as("旧错误信息必须清掉，否则下次失败会看到两条").isNull();
    }

    @Test
    @DisplayName("★ 重试：没失败的文档 → 40902（否则 AI 会对同一份文档跑两遍）")
    void retryRejectsNotFailedDocument() {
        long id = docId(uploadDoc("pending.txt", createKb("重试拒绝库")));

        post("/admin/documents/" + id + "/retry", adminToken, null)
                .assertCode(ErrorCode.STATUS_CONFLICT.getCode());
    }

    // ------------------------------------------------------------ 删除

    @Test
    @DisplayName("★ 删除：文档与 sys_file 都是逻辑删除，OSS 对象保留")
    void deleteIsSoftDelete() {
        long id = docId(uploadDoc("delete.txt", createKb("删除库")));
        Long fileId = jdbc.queryForObject(
                "SELECT file_id FROM document WHERE id = ?", Long.class, id);
        assertThat(fileId).isNotNull();

        delete("/admin/documents/" + id, adminToken).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM document WHERE id = ?", Integer.class, id))
                .as("document 是逻辑删除").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM sys_file WHERE id = ?", Integer.class, fileId))
                .as("sys_file 也要跟着逻辑删 —— 留着就是孤儿记录").isEqualTo(1);

        get("/admin/documents/" + id, adminToken)
                .assertCode(ErrorCode.DOCUMENT_NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------ 批量

    @Test
    @DisplayName("★ 批量重试：只重试失败的，返回实际条数")
    void batchRetryCountsOnlyFailed() {
        long kbId = createKb("批量重试库");
        long failed = docId(uploadDoc("f1.txt", kbId));
        long pending = docId(uploadDoc("f2.txt", kbId));

        put("/admin/documents/" + failed + "/status", adminToken,
                Map.of("parseStatus", "FAILED")).assertSuccess();

        ApiResult res = post("/admin/documents/batch-retry", adminToken,
                Map.of("ids", List.of(failed, pending))).assertSuccess();

        assertThat(res.data().asInt()).as("只有失败的那条算数").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT parse_status FROM document WHERE id = ?", String.class, failed))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT parse_status FROM document WHERE id = ?", String.class, pending))
                .as("本来就没失败，不该被动过").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("批量删除：返回实际删除条数，全部逻辑删除")
    void batchDeleteRemovesAll() {
        long kbId = createKb("批量删除库");
        long a = docId(uploadDoc("d1.txt", kbId));
        long b = docId(uploadDoc("d2.txt", kbId));

        ApiResult res = deleteWithBody("/admin/documents/batch",
                Map.of("ids", List.of(a, b)), adminToken).assertSuccess();

        assertThat(res.data().asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM document WHERE id IN (?, ?) AND deleted = 1",
                Integer.class, a, b)).isEqualTo(2);
    }

    @Test
    @DisplayName("批量：ids 为空 / 超过 100 条 → 40001")
    void batchValidatesIds() {
        post("/admin/documents/batch-retry", adminToken, Map.of("ids", List.of()))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            tooMany.add(i);
        }
        deleteWithBody("/admin/documents/batch", Map.of("ids", tooMany), adminToken)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------ 分块

    @Test
    @DisplayName("分块：分页生效，按 chunkIndex 升序")
    void chunksArePaginated() {
        long id = docId(uploadDoc("chunks.txt", createKb("分块库")));
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO document_chunk (id, user_id, knowledge_base_id, document_id,
                                                chunk_index, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW())
                    """, 900000L + i, userId(ADMIN_NAME), 1L, id, i, "第 " + i + " 段");
        }

        ApiResult res = get("/admin/documents/" + id + "/chunks?page=1&pageSize=2", adminToken)
                .assertSuccess();

        assertThat(res.data().path("total").asLong()).isEqualTo(3);
        assertThat(res.data().path("records").size()).isEqualTo(2);
        assertThat(res.data().path("records").get(0).path("chunkIndex").asInt()).isZero();
    }

    // ------------------------------------------------------------ 权限

    @Test
    @DisplayName("普通用户访问管理端文档接口 → 403")
    void normalUserIsForbidden() {
        get("/admin/documents", loginAsUser()).assertHttpStatus(403);
        delete("/admin/documents/1", loginAsUser()).assertHttpStatus(403);
    }

    // ------------------------------------------------------------ 辅助

    private long createKb(String name) {
        return post("/admin/knowledge-bases", adminToken, Map.of("name", name))
                .assertSuccess().longId();
    }

    private ApiResult uploadDoc(String filename, long kbId) {
        return upload("/admin/knowledge-bases/" + kbId + "/documents/upload", adminToken, "file",
                filename, "这是用于测试的文档正文。".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    /** 管理端文档 VO 的 id 字段叫 {@code documentId}，基类的 {@code longId()} 只认 {@code id}。 */
    private long docId(ApiResult res) {
        return Long.parseLong(res.assertSuccess().str("documentId"));
    }

    /** 带 body 的 DELETE —— 批量删除是这个形状，不是 POST。 */
    private ApiResult deleteWithBody(String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = rest.exchange(
                path, HttpMethod.DELETE, new HttpEntity<>(body, headers), JsonNode.class);
        return new ApiResult(response.getStatusCode().value(), response.getBody());
    }
}
