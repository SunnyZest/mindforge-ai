package com.zest.mindforgeai.api;

import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回收站（一期）：Offer 与面试日程这两种「删除时没有任何物理清理」的资源。
 *
 * <p>★ 三条最该守住的语义：
 * <ul>
 *   <li><b>恢复要真的把 deleted 改回 0</b> —— 用 MyBatis-Plus 的 updateById 是改不动的
 *       （全局逻辑删除会自动补 {@code deleted = 0} 条件），必须走原生 SQL；</li>
 *   <li><b>彻底删除只删逻辑删除的行</b> —— 如果期间已经被恢复，绝不能把正常数据删掉；</li>
 *   <li><b>回收站是私有的</b> —— 别人的记录既看不到，也恢复不了。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-22
 */
@DisplayName("回收站")
class RecycleBinApiTest extends AbstractApiTest {

    private static final String BASE = "/app/recycle-bin";

    private String createOffer(String token, String company) {
        return post("/app/offers", token, Map.of(
                "companyName", company,
                "positionName", "后端开发",
                "salaryAnnual", 300000,
                // 五个维度都是 @NotNull —— 少传一个就是 40001，与回收站无关
                "salaryScore", 8,
                "techScore", 8,
                "brandScore", 8,
                "wlbScore", 6,
                "commuteScore", 7)).assertSuccess().id();
    }

    /** 列表里是否存在某条原资源 */
    private boolean inRecycleBin(String token, String resourceId) {
        ApiResult r = get(BASE + "?page=1&pageSize=50", token).assertSuccess();
        for (var node : r.data().get("records")) {
            if (resourceId.equals(node.path("resourceId").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 取回收站里第一条记录的 id（恢复 / 彻底删除用） */
    private String firstBinId(String token) {
        return get(BASE, token).assertSuccess().data().get("records").get(0).path("id").asText();
    }

    @Test
    @DisplayName("删除 Offer 后出现在回收站，带类型中文名与剩余天数")
    void deletingOfferPutsItIntoRecycleBin() {
        String token = loginAsUser();
        String offerId = createOffer(token, "字节跳动");

        delete("/app/offers/" + offerId, token).assertSuccess();

        ApiResult r = get(BASE, token).assertSuccess();
        assertThat(r.data().get("total").asInt()).isEqualTo(1);

        var item = r.data().get("records").get(0);
        assertThat(item.path("resourceType").asText()).isEqualTo("OFFER");
        assertThat(item.path("resourceTypeName").asText()).isEqualTo("Offer");
        assertThat(item.path("resourceId").asText()).isEqualTo(offerId);
        assertThat(item.path("title").asText()).isEqualTo("字节跳动");
        assertThat(item.path("remainingDays").asLong()).isGreaterThan(0);
    }

    @Test
    @DisplayName("恢复后 Offer 回到列表，回收站记录消失")
    void restoreBringsOfferBack() {
        String token = loginAsUser();
        String offerId = createOffer(token, "腾讯");
        delete("/app/offers/" + offerId, token).assertSuccess();

        post(BASE + "/" + firstBinId(token) + "/restore", token, Map.of()).assertSuccess();

        // 详情能查到 = deleted 真的被改回 0 了
        get("/app/offers/" + offerId, token).assertSuccess();
        assertThat(inRecycleBin(token, offerId)).isFalse();
    }

    @Test
    @DisplayName("同一条记录不能恢复两次")
    void restoringTwiceFails() {
        String token = loginAsUser();
        String offerId = createOffer(token, "美团");
        delete("/app/offers/" + offerId, token).assertSuccess();

        String binId = firstBinId(token);
        post(BASE + "/" + binId + "/restore", token, Map.of()).assertSuccess();

        // 恢复成功后记录就被删掉了，第二次是「不存在」而不是「又恢复了一遍」
        assertThat(post(BASE + "/" + binId + "/restore", token, Map.of()).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("彻底删除后 Offer 真的没了")
    void purgeRemovesOfferPermanently() {
        String token = loginAsUser();
        String offerId = createOffer(token, "拼多多");
        delete("/app/offers/" + offerId, token).assertSuccess();

        delete(BASE + "/" + firstBinId(token), token).assertSuccess();

        assertThat(get("/app/offers/" + offerId, token).isSuccess()).isFalse();
        assertThat(inRecycleBin(token, offerId)).isFalse();
    }

    @Test
    @DisplayName("原记录已被恢复时，彻底删除不会误删正常数据")
    void purgeNeverTouchesLiveRow() {
        String token = loginAsUser();
        String offerId = createOffer(token, "小红书");
        delete("/app/offers/" + offerId, token).assertSuccess();
        String binId = firstBinId(token);

        // 手工构造「已经恢复过、但回收站记录还残留」的异常状态：
        // 这是本类最危险的场景 —— 如果 purge 不带 deleted = 1 条件，
        // 一条正常数据就会被「彻底删除」干掉
        jdbc.update("UPDATE job_offer SET deleted = 0 WHERE id = ?", Long.valueOf(offerId));

        delete(BASE + "/" + binId, token).assertSuccess();

        get("/app/offers/" + offerId, token).assertSuccess();
    }

    @Test
    @DisplayName("回收站是私有的：别人看不到也动不了")
    void recycleBinIsPrivate() {
        String userToken = loginAsUser();
        String offerId = createOffer(userToken, "京东");
        delete("/app/offers/" + offerId, userToken).assertSuccess();

        String adminToken = loginAsAdmin();
        ApiResult adminList = get(BASE, adminToken).assertSuccess();
        assertThat(adminList.data().get("total").asInt()).isZero();

        // 拿别人的回收站记录 id 去恢复 —— 应该是「不存在」
        String binId = firstBinId(userToken);
        assertThat(post(BASE + "/" + binId + "/restore", adminToken, Map.of()).isSuccess()).isFalse();
    }
}
