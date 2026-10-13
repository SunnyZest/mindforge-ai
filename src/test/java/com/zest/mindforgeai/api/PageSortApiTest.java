package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页排序（sortBy + sortOrder）。
 *
 * <p>这里最要紧的一条是<b>白名单</b>：MyBatis-Plus 的排序是把字段名拼进 SQL 的，
 * 前端能传任意字符串的话就是 SQL 注入。所以「不在白名单里的字段必须 40001」
 * 比「排序能生效」更重要 —— 后者出错只是功能不好用，前者出错是安全漏洞。
 *
 * <p>另外要单独验证<b>自定义 XML 分页</b>那条路：那几个接口的 SQL 里原来自带
 * ORDER BY，被 MP 追加的排序会排到它后面而完全不生效，所以 XML 里的 ORDER BY
 * 已经去掉了。这条一旦回归，表现是「排序参数传了但没反应」—— 很难发现。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("分页排序")
class PageSortApiTest extends AbstractApiTest {

    /** 建 n 个职位，标题带序号，方便断言顺序。 */
    private List<Long> createJobs(String prefix, int count) {
        String token = loginAsUser();
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(post("/app/jobs", token, Map.of(
                    "title", prefix + i,
                    "companyName", "公司" + prefix + i,
                    "jobDescription", "JD " + i)).assertSuccess().longId());
        }
        return ids;
    }

    private List<String> titlesOf(ApiResult result) {
        List<String> titles = new ArrayList<>();
        result.data().get("records").forEach(node -> titles.add(node.path("title").asText()));
        return titles;
    }

    // ---------- ★ 白名单（安全） ----------

    @Test
    @DisplayName("★ 白名单之外的字段 → 40001，绝不拼进 SQL")
    void unknownSortFieldIsRejected() {
        createJobs("岗", 1);

        ApiResult result = get("/app/jobs?sortBy=" + "id%20desc--" + "&sortOrder=desc", loginAsUser());

        result.assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 排序字段里带 SQL 片段 → 40001（白名单挡住了）")
    void sqlInjectionAttemptIsRejected() {
        String token = loginAsUser();

        for (String evil : new String[]{"id;DROP TABLE job", "1=1", "id,title", "(SELECT 1)"}) {
            get("/app/jobs?sortBy=" + java.net.URLEncoder.encode(evil, java.nio.charset.StandardCharsets.UTF_8),
                    token).assertCode(ErrorCode.PARAM_INVALID.getCode());
        }
        // 表还在
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job", Integer.class)).isNotNull();
    }

    @Test
    @DisplayName("报错信息里列出可选字段（前端调错能自己看出来）")
    void errorListsAllowedFields() {
        ApiResult result = get("/app/jobs?sortBy=nope", loginAsUser());

        assertThat(result.message())
                .as("要把白名单列出来，否则前端只能来问")
                .contains("title")
                .contains("createdAt");
    }

    // ---------- 排序真的生效 ----------

    @Test
    @DisplayName("按标题升序 / 降序")
    void sortsByTitle() {
        createJobs("岗位", 3);
        String token = loginAsUser();

        assertThat(titlesOf(get("/app/jobs?sortBy=title&sortOrder=asc", token).assertSuccess()))
                .containsExactly("岗位1", "岗位2", "岗位3");
        assertThat(titlesOf(get("/app/jobs?sortBy=title&sortOrder=desc", token).assertSuccess()))
                .containsExactly("岗位3", "岗位2", "岗位1");
    }

    @Test
    @DisplayName("按公司名排序（另一个白名单字段）")
    void sortsByCompanyName() {
        createJobs("岗", 3);
        String token = loginAsUser();

        assertThat(titlesOf(get("/app/jobs?sortBy=companyName&sortOrder=asc", token).assertSuccess()))
                .containsExactly("岗1", "岗2", "岗3");
    }

    @Test
    @DisplayName("sortOrder 大小写不敏感；乱填当降序（宽松处理，不给前端添麻烦）")
    void sortOrderIsLenient() {
        createJobs("岗位", 2);
        String token = loginAsUser();

        assertThat(titlesOf(get("/app/jobs?sortBy=title&sortOrder=ASC", token).assertSuccess()))
                .containsExactly("岗位1", "岗位2");
        assertThat(titlesOf(get("/app/jobs?sortBy=title&sortOrder=什么鬼", token).assertSuccess()))
                .as("不认的值当降序，而不是报错").containsExactly("岗位2", "岗位1");
    }

    @Test
    @DisplayName("★ 不传 sortBy → 走兜底排序（行为跟以前一样，不会变成无序）")
    void fallsBackWhenNoSortGiven() {
        List<Long> ids = createJobs("岗位", 3);
        String token = loginAsUser();

        List<String> titles = titlesOf(get("/app/jobs", token).assertSuccess());

        assertThat(titles)
                .as("兜底是 id 倒序 —— 没有 ORDER BY 的话分页会出现「同一条在两页都出现」")
                .containsExactly("岗位3", "岗位2", "岗位1");
        assertThat(ids).hasSize(3);
    }

    @Test
    @DisplayName("分页 + 排序一起用：第 2 页的顺序接着第 1 页")
    void sortWorksWithPaging() {
        createJobs("岗位", 5);
        String token = loginAsUser();

        assertThat(titlesOf(get("/app/jobs?page=1&pageSize=2&sortBy=title&sortOrder=asc", token)
                .assertSuccess())).containsExactly("岗位1", "岗位2");
        assertThat(titlesOf(get("/app/jobs?page=2&pageSize=2&sortBy=title&sortOrder=asc", token)
                .assertSuccess())).containsExactly("岗位3", "岗位4");
    }

    // ---------- ★ 自定义 XML 分页那条路 ----------

    @Test
    @DisplayName("★ 知识库（走自定义 XML 分页）也能按名字排序")
    void xmlBasedPagingSortsToo() {
        String token = loginAsUser();
        for (String name : new String[]{"丙库", "甲库", "乙库"}) {
            post("/app/knowledge-bases", token, Map.of("name", name)).assertSuccess();
        }

        List<String> names = new ArrayList<>();
        get("/app/knowledge-bases?sortBy=name&sortOrder=asc", token).assertSuccess()
                .data().get("records").forEach(node -> names.add(node.path("name").asText()));

        assertThat(names)
                .as("XML 里原来写死的 ORDER BY kb.id DESC 已经去掉，改由 MP 追加 —— "
                        + "如果这条挂了，说明 XML 里又被加回了 ORDER BY")
                .hasSize(3);
        assertThat(names).isSorted();
    }

    @Test
    @DisplayName("★ 管理端文档列表（XML 分页）排序生效")
    void adminDocumentPagingSortsToo() {
        String token = loginAsUser();
        long kbId = post("/app/knowledge-bases", token, Map.of("name", "排序测试库"))
                .assertSuccess().longId();
        for (String name : new String[]{"c.txt", "a.txt", "b.txt"}) {
            upload("/app/knowledge-bases/" + kbId + "/documents", token, "file",
                    name, ("内容 " + name).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "text/plain").assertSuccess();
        }

        String adminToken = loginAsAdmin();
        List<String> fileNames = new ArrayList<>();
        get("/admin/documents?sortBy=fileName&sortOrder=asc", adminToken).assertSuccess()
                .data().get("records").forEach(node -> fileNames.add(node.path("fileName").asText()));

        assertThat(fileNames).hasSize(3).isSorted();
    }

    // ---------- 分块的兜底方向是升序 ----------

    @Test
    @DisplayName("★ 分块列表默认按 chunkIndex 升序（它天然有顺序，倒着看没意义）")
    void chunkDefaultIsAscending() {
        String token = loginAsUser();
        long kbId = post("/app/knowledge-bases", token, Map.of("name", "分块排序库"))
                .assertSuccess().longId();
        String docIdText = upload("/app/knowledge-bases/" + kbId + "/documents", token, "file",
                "分块.txt", "第一段。\n\n第二段。\n\n第三段。".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/plain").assertSuccess().str("documentId");

        // ⚠️ DocumentVO 的主键字段叫 documentId，不是 id
        long docId = Long.parseLong(docIdText);
        // 直接造几条分块，免得等异步流水线
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO document_chunk (id, user_id, knowledge_base_id, document_id, "
                            + "chunk_index, content, token_count, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 10, NOW())",
                    com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), userId(USER_NAME), kbId, docId, i, "第 " + i + " 块");
        }

        List<Integer> indexes = new ArrayList<>();
        get("/admin/documents/" + docId + "/chunks", loginAsAdmin()).assertSuccess()
                .data().get("records").forEach(node -> indexes.add(node.path("chunkIndex").asInt()));

        assertThat(indexes).containsExactly(1, 2, 3);
    }
}
