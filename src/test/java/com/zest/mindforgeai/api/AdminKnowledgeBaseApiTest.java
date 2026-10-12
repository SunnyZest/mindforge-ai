package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 管理端知识库创建与网页 URL 导入集成测试。
 *
 * @author crx
 * @since 2026-10-12
 */
@DisplayName("管理端 - 知识库与文档录入")
class AdminKnowledgeBaseApiTest extends AbstractApiTest {

    private String adminToken;

    @BeforeEach
    void setup() {
        adminToken = loginAsAdmin();
    }

    @Test
    @DisplayName("管理员创建知识库并修改状态")
    void testCreateAndUpdateKnowledgeBase() {
        // 1. 创建知识库
        ApiResult createRes = post("/admin/knowledge-bases", adminToken, Map.of(
                "name", "官方面试真题库",
                "description", "包含各大厂 Java 高频面试考点与八股解析"
        ));
        createRes.assertSuccess();
        long kbId = createRes.longId();
        assertThat(kbId).isPositive();
        assertThat(createRes.str("name")).isEqualTo("官方面试真题库");

        // 2. 修改知识库
        ApiResult updateRes = put("/admin/knowledge-bases/" + kbId, adminToken, Map.of(
                "name", "官方面试真题库(2026最新)",
                "status", 1
        ));
        updateRes.assertSuccess();

        // 3. 查详情
        ApiResult detailRes = get("/admin/knowledge-bases/" + kbId, adminToken);
        detailRes.assertSuccess();
        assertThat(detailRes.str("name")).isEqualTo("官方面试真题库(2026最新)");
    }

    @Test
    @DisplayName("管理员输入网页 URL 一键提取正文并转化为 Markdown 文档入库")
    void testImportDocumentFromUrl() {
        // 1. 先建一个库
        long kbId = post("/admin/knowledge-bases", adminToken, Map.of(
                "name", "前端与后端技术库"
        )).assertSuccess().longId();

        // 2. Mock 提取工具返回
        String sampleUrl = "https://example.com/java-basics";
        when(aiServiceClient.extractUrl(sampleUrl)).thenReturn(
                new AiServiceClient.UrlExtractData(
                        sampleUrl,
                        "Java 核心知识点",
                        "## Java 基础\n\nHashMap 底层采用数组加链表或红黑树实现。",
                        50
                )
        );

        // 3. 调 URL 导入接口
        ApiResult importRes = post("/admin/knowledge-bases/" + kbId + "/documents/import-url", adminToken, Map.of(
                "url", sampleUrl
        ));
        importRes.assertSuccess();

        String docId = importRes.str("documentId");
        assertThat(docId).isNotNull();
        assertThat(importRes.str("fileName")).contains("Java 核心知识点");
        assertThat(importRes.str("fileType")).isEqualTo("MD");
        assertThat(importRes.str("status")).isEqualTo("UPLOADED");
    }
}
