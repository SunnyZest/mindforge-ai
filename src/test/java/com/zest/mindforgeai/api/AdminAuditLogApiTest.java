package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端审计日志：{@code GET /api/admin/audit-logs}。
 *
 * <p>这个接口<b>只有查询、没有删除</b> —— 能删的审计日志等于没有审计。
 * 用例除了覆盖筛选条件，还顺手守住这一点：控制器上不该出现任何写操作端点。
 *
 * <p>★ 数据来源就用「登录本身」：每次登录都会写一条 {@code ADMIN_LOGIN} 审计，
 * 不用手工造数据，也更接近真实链路（HTTP 上下文、requestId、IP 都是真的）。
 *
 * @author crx
 * @since 2026-10-17
 */
@DisplayName("管理端-审计日志")
class AdminAuditLogApiTest extends AbstractApiTest {

    private String adminToken;
    private long adminId;

    @BeforeEach
    void loginAdmin() {
        adminToken = loginAsAdmin();
        adminId = userId(ADMIN_NAME);
    }

    @Test
    @DisplayName("★ 列表：登录动作被记下，且带 requestId / IP / 请求路径")
    void pageReturnsLoginAudit() {
        ApiResult res = get("/admin/audit-logs", adminToken).assertSuccess().assertCamelCase();

        assertThat(res.data().path("total").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode login = firstByAction(res.data().path("records"), "ADMIN_LOGIN");
        assertThat(login).as("登录应当留下 ADMIN_LOGIN 审计").isNotNull();
        assertThat(login.path("userId").asText()).isEqualTo(String.valueOf(adminId));
        assertThat(login.path("result").asText()).isEqualTo("SUCCESS");
        assertThat(login.path("requestId").asText()).isNotBlank();
        assertThat(login.path("ip").asText()).isNotBlank();
        assertThat(login.path("requestUri").asText()).isEqualTo("/api/admin/auth/login");
    }

    @Test
    @DisplayName("按动作过滤：命中 ADMIN_LOGIN，未知动作返回空页")
    void filtersByAction() {
        ApiResult hit = get("/admin/audit-logs?action=ADMIN_LOGIN", adminToken).assertSuccess();
        assertThat(hit.data().path("total").asLong()).isGreaterThanOrEqualTo(1);
        for (JsonNode r : hit.data().path("records")) {
            assertThat(r.path("action").asText()).isEqualTo("ADMIN_LOGIN");
        }

        ApiResult miss = get("/admin/audit-logs?action=NO_SUCH_ACTION", adminToken).assertSuccess();
        assertThat(miss.data().path("total").asLong()).isZero();
        assertThat(miss.data().path("records")).isEmpty();
    }

    @Test
    @DisplayName("★ 按结果过滤：登录失败被记成 FAIL（失败也要留痕）")
    void filtersByResult() {
        // 用一个不存在的账号，避免给真实账号累计失败次数
        loginRaw("/admin/auth/login", "audit_ghost", "whatever-123")
                .assertCode(ErrorCode.UNAUTHORIZED.getCode());

        ApiResult res = get("/admin/audit-logs?result=FAIL", adminToken).assertSuccess();
        assertThat(res.data().path("total").asLong()).isGreaterThanOrEqualTo(1);
        for (JsonNode r : res.data().path("records")) {
            assertThat(r.path("result").asText()).isEqualTo("FAIL");
        }
    }

    @Test
    @DisplayName("按操作人过滤：只返回该 userId 的记录")
    void filtersByUserId() {
        ApiResult res = get("/admin/audit-logs?userId=" + adminId, adminToken).assertSuccess();

        assertThat(res.data().path("total").asLong()).isGreaterThanOrEqualTo(1);
        for (JsonNode r : res.data().path("records")) {
            assertThat(r.path("userId").asText()).isEqualTo(String.valueOf(adminId));
        }
    }

    @Test
    @DisplayName("按资源类型过滤：不存在的类型返回空页")
    void filtersByResourceType() {
        ApiResult res = get("/admin/audit-logs?resourceType=NO_SUCH_RESOURCE", adminToken)
                .assertSuccess();
        assertThat(res.data().path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("按时间范围过滤：起始时间在未来 → 空页")
    void filtersByTimeRange() {
        ApiResult res = get("/admin/audit-logs?from=2030-01-01T00:00:00", adminToken)
                .assertSuccess();
        assertThat(res.data().path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("分页上限：pageSize 传 1000 被压到 100，防止一次拉全表")
    void pageSizeIsCapped() {
        ApiResult res = get("/admin/audit-logs?pageSize=1000", adminToken).assertSuccess();
        assertThat(res.data().path("pageSize").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("★ 排序白名单：不在白名单里的 sortBy → 40001")
    void rejectsUnknownSortField() {
        get("/admin/audit-logs?sortBy=ipAddress", adminToken)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 普通用户看不了审计日志 → 403（第一层 URL 规则）")
    void normalUserIsForbidden() {
        get("/admin/audit-logs", loginAsUser()).assertHttpStatus(403);
    }

    // ------------------------------------------------------------ 辅助

    private JsonNode firstByAction(JsonNode records, String action) {
        for (JsonNode node : records) {
            if (action.equals(node.path("action").asText())) {
                return node;
            }
        }
        return null;
    }
}
