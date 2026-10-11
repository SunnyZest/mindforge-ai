package com.zest.mindforgeai.api;

import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端高危操作告警。
 *
 * <p>触发点在审计写入处（{@code AuditServiceImpl}），所以告警测试走真实链路：
 * 用管理端接口做一次高危操作（建角色），再查告警。
 * 这比直接往 {@code sys_alert} 插数据更能守住「高危审计必有告警」这条链路。
 *
 * @author crx
 * @since 2026-10-11
 */
@DisplayName("管理端 - 高危操作告警")
class AlertApiTest extends AbstractApiTest {

    private static final String PATH = "/admin/alerts";

    /** 高危触发器：建角色（ROLE_CREATE 在高危清单里，SUPER_ADMIN 有 admin:role:create）。 */
    private ApiResult createRole(String code) {
        return post("/admin/roles", loginAsAdmin(), Map.of(
                "name", "测试角色-" + code,
                "code", code,
                "description", "告警测试"));
    }

    private long unreadCount() {
        return get(PATH + "/unread-count", loginAsAdmin()).assertSuccess()
                .data().asLong();
    }

    // ---------- 生成 ----------

    @Test
    @DisplayName("★ 高危操作（建角色）自动产生未读告警，字段对齐审计")
    void highRiskOpCreatesUnreadAlert() {
        long before = unreadCount();

        createRole("ALERT_T1_" + System.nanoTime()).assertSuccess();

        long after = unreadCount();
        assertThat(after).as("高危操作后未读数应 +1").isEqualTo(before + 1);

        ApiResult result = get(PATH + "?pageSize=5&sortBy=createdAt&sortOrder=desc", loginAsAdmin())
                .assertSuccess().assertCamelCase();
        var first = result.data().get("records").get(0);

        assertThat(first.path("action").asText()).isEqualTo("ROLE_CREATE");
        assertThat(first.path("read").asBoolean()).isFalse();
        assertThat(first.path("title").asText()).contains("高危操作").contains("（成功）");
        assertThat(first.path("detailJson").asText()).contains("ALERT_T1_");
        assertThat(first.path("id").asText()).as("id 要是字符串（JS 精度）").isNotBlank();
    }

    @Test
    @DisplayName("★ 告警是审计的影子：没有审计点的参数冲突（code 重复）不产生告警")
    void conflictWithoutAuditDoesNotAlert() {
        String code = "ALERT_DUP_" + System.nanoTime();
        createRole(code).assertSuccess();
        long afterFirst = unreadCount();

        // 同 code 再建一次 → 40902。这是用户输入错误、在 service 校验层就被拦下，
        // 根本不会写审计（审计是「发生过什么」的记录，参数冲突不算发生过高危操作），
        // 所以也不该告警 —— 告警必须严格跟着审计走，否则两边对不上。
        createRole(code).assertCode(40902);

        assertThat(unreadCount()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("普通操作（建知识库）不产生告警")
    void normalOpsDoNotAlert() {
        long before = unreadCount();

        post("/app/knowledge-bases", loginAsUser(), Map.of("name", "普通操作库")).assertSuccess();

        assertThat(unreadCount()).as("KB_CREATE 不在高危清单里").isEqualTo(before);
    }

    // ---------- 已读 ----------

    @Test
    @DisplayName("标记已读：未读数归零、列表 read=true；重复标记幂等且不覆盖已读信息")
    void markReadIsIdempotent() {
        createRole("ALERT_READ_" + System.nanoTime()).assertSuccess();
        var first = get(PATH + "?pageSize=1&unreadOnly=true", loginAsAdmin()).assertSuccess()
                .data().get("records").get(0);
        String alertId = first.path("id").asText();

        put(PATH + "/" + alertId + "/read", loginAsAdmin(), null).assertSuccess();
        assertThat(unreadCount()).isZero();

        var read = get(PATH + "?pageSize=1", loginAsAdmin()).assertSuccess()
                .data().get("records").get(0);
        assertThat(read.path("read").asBoolean()).isTrue();
        String readBy = read.path("readBy").asText();
        String readAt = read.path("readAt").asText();

        // 幂等：再标一次不报错，且「谁在什么时候读的」不被覆盖
        put(PATH + "/" + alertId + "/read", loginAsAdmin(), null).assertSuccess();
        var again = get(PATH + "?pageSize=1", loginAsAdmin()).assertSuccess()
                .data().get("records").get(0);
        assertThat(again.path("readBy").asText()).isEqualTo(readBy);
        assertThat(again.path("readAt").asText()).isEqualTo(readAt);
    }

    @Test
    @DisplayName("全部标记已读，返回本次新标记条数")
    void markAllRead() {
        createRole("ALERT_ALL1_" + System.nanoTime()).assertSuccess();
        createRole("ALERT_ALL2_" + System.nanoTime()).assertSuccess();
        long unread = unreadCount();
        assertThat(unread).isGreaterThanOrEqualTo(2);

        int marked = put(PATH + "/read-all", loginAsAdmin(), null).assertSuccess().data().asInt();
        assertThat(marked).isEqualTo(unread);
        assertThat(unreadCount()).isZero();
    }

    @Test
    @DisplayName("批量选定标记已读，返回本次新标记条数")
    void batchMarkRead() {
        createRole("ALERT_B1_" + System.nanoTime()).assertSuccess();
        createRole("ALERT_B2_" + System.nanoTime()).assertSuccess();

        var listRes = get(PATH + "?pageSize=2&sortBy=createdAt&sortOrder=desc", loginAsAdmin())
                .assertSuccess().data().get("records");
        long id1 = listRes.get(0).get("id").asLong();
        long id2 = listRes.get(1).get("id").asLong();

        int marked = put(PATH + "/batch-read", loginAsAdmin(), Map.of("ids", java.util.List.of(id1, id2)))
                .assertSuccess().data().asInt();
        assertThat(marked).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("标记不存在的告警 → 404")
    void markMissingAlertIsNotFound() {
        put(PATH + "/999999999/read", loginAsAdmin(), null).assertCode(40415);
    }

    // ---------- 鉴权与契约 ----------

    @Test
    @DisplayName("普通用户 403、未登录 401")
    void requiresAdminAuth() {
        get(PATH + "/unread-count", loginAsUser()).assertHttpStatus(403);
        get(PATH + "/unread-count", null).assertHttpStatus(401);
    }

    @Test
    @DisplayName("排序字段白名单：非法 sortBy → 40001")
    void rejectsUnknownSortField() {
        get(PATH + "?sortBy=drop_table", loginAsAdmin()).assertCode(40001);
    }
}
