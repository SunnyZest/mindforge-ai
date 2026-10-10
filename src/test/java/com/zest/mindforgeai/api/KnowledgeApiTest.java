package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库接口：CRUD + stats + 归属校验。
 *
 * <p>文档/分块的链路依赖 Python 侧的解析流水线（要真调模型），
 * 那部分留给 e2e；这里只覆盖 Java 自己能负责的边界。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("知识库")
class KnowledgeApiTest extends AbstractApiTest {

    private String token;
    private long kbId;

    @BeforeEach
    void createOne() {
        token = loginAsUser();
        kbId = post("/app/knowledge-bases", token, Map.of("name", "测试知识库"))
                .assertSuccess().longId();
    }

    @Test
    @DisplayName("创建后落库")
    void createPersists() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE id = ? AND deleted = 0",
                Integer.class, kbId)).isEqualTo(1);
    }

    @Test
    @DisplayName("名称超长（101 字符）→ 40001")
    void nameOverLimitIsRejected() {
        post("/app/knowledge-bases", token, Map.of("name", "x".repeat(101)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("名称为空 → 40001")
    void blankNameIsRejected() {
        post("/app/knowledge-bases", token, Map.of("name", ""))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("描述超长（501 字符）→ 40001")
    void descriptionOverLimitIsRejected() {
        post("/app/knowledge-bases", token, Map.of("name", "x", "description", "y".repeat(501)))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("列表分页 + camelCase")
    void listIsPaged() {
        ApiResult result = get("/app/knowledge-bases?page=1&pageSize=5", token)
                .assertSuccess().assertCamelCase();

        assertThat(result.data().get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(result.data().get("records").size()).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("详情")
    void detail() {
        get("/app/knowledge-bases/" + kbId, token).assertSuccess().assertCamelCase();
    }

    @Test
    @DisplayName("修改名称")
    void updateName() {
        put("/app/knowledge-bases/" + kbId, token,
                Map.of("name", "改过名字的知识库", "description", "新描述")).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT name FROM knowledge_base WHERE id = ?", String.class, kbId))
                .isEqualTo("改过名字的知识库");
    }

    @Test
    @DisplayName("stats 能取到（空知识库也不能报错）")
    void statsWorksOnEmptyKnowledgeBase() {
        get("/app/knowledge-bases/" + kbId + "/stats", token).assertSuccess().assertCamelCase();
    }

    @Test
    @DisplayName("删除：逻辑删知识库")
    void deleteIsLogical() {
        delete("/app/knowledge-bases/" + kbId, token).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE id = ? AND deleted = 0",
                Integer.class, kbId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE id = ?", Integer.class, kbId))
                .as("有 deleted 列的表走逻辑删除，行还在").isEqualTo(1);
    }

    @Test
    @DisplayName("不存在的知识库：404")
    void missingIsNotFound() {
        get("/app/knowledge-bases/99999999", token)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("★ 别人的知识库：404，且不能改、不能删")
    void otherUsersKnowledgeBaseIsInaccessible() {
        createAccount("t_other_user");
        String otherToken = loginRaw("/app/auth/login", "t_other_user", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        get("/app/knowledge-bases/" + kbId, otherToken).assertHttpStatus(404);
        put("/app/knowledge-bases/" + kbId, otherToken, Map.of("name", "劫持")).assertHttpStatus(404);
        delete("/app/knowledge-bases/" + kbId, otherToken).assertHttpStatus(404);

        assertThat(jdbc.queryForObject(
                "SELECT name FROM knowledge_base WHERE id = ?", String.class, kbId))
                .as("被别人改过就说明越权成功了").isEqualTo("测试知识库");
    }

    @Test
    @DisplayName("普通用户访问管理端知识库列表：403")
    void normalUserCannotListAdminKnowledgeBases() {
        get("/admin/knowledge-bases", token).assertHttpStatus(403);
    }
}
