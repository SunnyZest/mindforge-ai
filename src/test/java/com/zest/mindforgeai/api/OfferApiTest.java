package com.zest.mindforgeai.api;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Offer 对比（五维打分表）。
 *
 * <p>重点在两块：CRUD 的常规约定（归属 404、状态机、局部更新），
 * 以及 <b>对比算法</b> —— 权重归一化、加权排名、并列处理、各维度最优、建议文案。
 *
 * @author crx
 * @since 2026-10-14
 */
@DisplayName("Offer 对比")
class OfferApiTest extends AbstractApiTest {

    private static final String PATH = "/app/offers";

    private String token;
    private Long idA;
    private Long idB;
    private Long idC;

    /**
     * 三家刻意拉开差异，用来验证「权重改变排名」：
     * <pre>
     * A 高薪公司  薪资 10 · 技术 4 · 背书 4 · 加班 4 · 通勤 4   → 等权 5.2
     * B 均衡公司  薪资  6 · 技术 8 · 背书 6 · 加班 6 · 通勤 6   → 等权 6.4
     * C 佛系公司  薪资  4 · 技术 4 · 背书 6 · 加班 10 · 通勤 8  → 等权 6.4
     * </pre>
     * 等权下 B / C 并列第一；把权重压到薪资则 A 翻盘，压到加班则 C 第一。
     */
    @BeforeEach
    void createThreeOffers() {
        token = loginAsUser();
        idA = createOffer("A 高薪公司", 30, 10, 4, 4, 4, 4);
        idB = createOffer("B 均衡公司", 25, 6, 8, 6, 6, 6);
        idC = createOffer("C 佛系公司", 20, 4, 4, 6, 10, 8);
    }

    private Long createOffer(String company, double salary, int salaryScore, int tech,
                             int brand, int wlb, int commute) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", company);
        body.put("positionName", "后端开发工程师");
        body.put("salaryAnnual", salary);
        body.put("salaryScore", salaryScore);
        body.put("techScore", tech);
        body.put("brandScore", brand);
        body.put("wlbScore", wlb);
        body.put("commuteScore", commute);
        return post(PATH, token, body).assertSuccess().longId();
    }

    /** 按 id 找对比结果里的某一项 */
    private com.fasterxml.jackson.databind.JsonNode itemOf(ApiResult result, Long offerId) {
        for (var item : result.data().path("offers")) {
            if (String.valueOf(offerId).equals(item.path("offerId").asText())) {
                return item;
            }
        }
        throw new AssertionError("对比结果里没有 offerId=" + offerId);
    }

    // ---------- CRUD ----------

    @Test
    @DisplayName("创建：默认状态 PENDING，五维分数原样返回")
    void createOffer() {
        var data = post(PATH, token, Map.of(
                "companyName", "某大厂", "positionName", "资深工程师", "salaryAnnual", 45,
                "salaryScore", 9, "techScore", 8, "brandScore", 10, "wlbScore", 3, "commuteScore", 6))
                .assertSuccess().assertCamelCase().data();

        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("salaryScore").asInt()).isEqualTo(9);
        assertThat(data.path("wlbScore").asInt()).isEqualTo(3);
        assertThat(data.path("id").asText()).isNotBlank();
    }

    @Test
    @DisplayName("创建：分数越界（0 或 11）→ 40001")
    void createRejectsOutOfRangeScore() {
        post(PATH, token, Map.of("companyName", "X", "salaryScore", 0,
                "techScore", 5, "brandScore", 5, "wlbScore", 5, "commuteScore", 5))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        post(PATH, token, Map.of("companyName", "X", "salaryScore", 11,
                "techScore", 5, "brandScore", 5, "wlbScore", 5, "commuteScore", 5))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("创建：公司名必填")
    void createRequiresCompanyName() {
        post(PATH, token, Map.of("salaryScore", 5, "techScore", 5,
                "brandScore", 5, "wlbScore", 5, "commuteScore", 5))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("列表：分页 + 状态筛选 + 关键词；非法 sortBy → 40001")
    void listWithFilterAndSort() {
        var all = get(PATH + "?pageSize=10", token).assertSuccess().assertCamelCase();
        assertThat(all.data().path("total").asInt()).isEqualTo(3);

        // 关键词匹配公司名
        var filtered = get(PATH + "?keyword=佛系", token).assertSuccess();
        assertThat(filtered.data().path("total").asInt()).isEqualTo(1);

        // 状态筛选：都还是 PENDING
        assertThat(get(PATH + "?status=ACCEPTED", token).assertSuccess()
                .data().path("total").asInt()).isZero();
        assertThat(get(PATH + "?status=PENDING", token).assertSuccess()
                .data().path("total").asInt()).isEqualTo(3);

        // 排序白名单
        get(PATH + "?sortBy=salaryAnnual&sortOrder=asc", token).assertSuccess();
        get(PATH + "?sortBy=drop_table", token).assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("修改：只更新传了的字段，没传的保持不变")
    void updateOnlyProvidedFields() {
        put(PATH + "/" + idA, token, Map.of("salaryScore", 3)).assertSuccess();

        var data = get(PATH + "/" + idA, token).assertSuccess().data();
        assertThat(data.path("salaryScore").asInt()).isEqualTo(3);
        assertThat(data.path("techScore").asInt()).as("没传的维度不该被清掉").isEqualTo(4);
        assertThat(data.path("companyName").asText()).isEqualTo("A 高薪公司");
    }

    @Test
    @DisplayName("状态流转：PENDING → ACCEPTED → DECLINED；非法值 40001")
    void updateStatus() {
        assertThat(put(PATH + "/" + idB + "/status", token, Map.of("status", "ACCEPTED"))
                .assertSuccess().data().path("status").asText()).isEqualTo("ACCEPTED");

        assertThat(put(PATH + "/" + idB + "/status", token, Map.of("status", "DECLINED"))
                .assertSuccess().data().path("status").asText()).isEqualTo("DECLINED");

        put(PATH + "/" + idB + "/status", token, Map.of("status", "MAYBE"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("删除后详情 404、列表不再包含")
    void deleteOffer() {
        delete(PATH + "/" + idC, token).assertSuccess();
        get(PATH + "/" + idC, token).assertCode(ErrorCode.OFFER_NOT_FOUND.getCode());
        assertThat(get(PATH, token).assertSuccess().data().path("total").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("别人的 Offer：详情 / 修改 / 删除一律 404")
    void otherUsersOfferIsNotFound() {
        createAccount("t_other_offer");
        String otherToken = loginRaw("/app/auth/login", "t_other_offer", TEST_PASSWORD)
                .assertSuccess().data().path("accessToken").asText();

        get(PATH + "/" + idA, otherToken).assertCode(ErrorCode.OFFER_NOT_FOUND.getCode());
        put(PATH + "/" + idA, otherToken, Map.of("salaryScore", 1))
                .assertCode(ErrorCode.OFFER_NOT_FOUND.getCode());
        delete(PATH + "/" + idA, otherToken).assertCode(ErrorCode.OFFER_NOT_FOUND.getCode());
    }

    // ---------- 对比：核心算法 ----------

    @Test
    @DisplayName("★ 等权对比：B / C 并列第一，同分同名次")
    void compareWithEqualWeights() {
        ApiResult result = post(PATH + "/compare", token,
                Map.of("offerIds", List.of(String.valueOf(idA), String.valueOf(idB), String.valueOf(idC))))
                .assertSuccess().assertCamelCase();

        var data = result.data();
        // 权重不传 = 五维等权
        for (String dim : List.of("salary", "tech", "brand", "wlb", "commute")) {
            assertThat(data.path("weights").path(dim).asDouble())
                    .as("等权时 " + dim + " 应为 0.2").isCloseTo(0.2, within(0.001));
        }

        // A = 5.2；B = 6.4；C = 6.4
        assertThat(itemOf(result, idA).path("totalScore").asDouble()).isCloseTo(5.2, within(0.01));
        assertThat(itemOf(result, idB).path("totalScore").asDouble()).isCloseTo(6.4, within(0.01));
        assertThat(itemOf(result, idC).path("totalScore").asDouble()).isCloseTo(6.4, within(0.01));

        // B 和 C 并列第 1，A 第 3（同分同名次、跳号）
        assertThat(itemOf(result, idB).path("rank").asInt()).isEqualTo(1);
        assertThat(itemOf(result, idC).path("rank").asInt()).isEqualTo(1);
        assertThat(itemOf(result, idA).path("rank").asInt()).isEqualTo(3);

        // 结果按总分降序：第一名在最前
        assertThat(data.path("offers").get(0).path("totalScore").asDouble()).isEqualTo(6.4);

        // 各维度最优
        assertThat(data.path("bestByDimension").path("salary").get(0).asText())
                .isEqualTo(String.valueOf(idA));
        assertThat(data.path("bestByDimension").path("tech").get(0).asText())
                .isEqualTo(String.valueOf(idB));
        assertThat(data.path("bestByDimension").path("wlb").get(0).asText())
                .isEqualTo(String.valueOf(idC));

        // 并列第一 → 建议里应说明「打平」
        assertThat(data.path("recommendation").asText()).contains("相同");
    }

    @Test
    @DisplayName("★★ 权重改变排名：压薪资则 A 翻盘，压加班则 C 第一")
    void weightsChangeRanking() {
        // 只给薪资权重 → A（薪资 10 分）第一
        ApiResult bySalary = post(PATH + "/compare", token, Map.of(
                "offerIds", List.of(String.valueOf(idA), String.valueOf(idB), String.valueOf(idC)),
                "salaryWeight", 100)).assertSuccess();

        assertThat(bySalary.data().path("weights").path("salary").asDouble())
                .as("只传薪资权重时应归一化为 1.0").isCloseTo(1.0, within(0.001));
        assertThat(bySalary.data().path("weights").path("tech").asDouble()).isZero();
        assertThat(itemOf(bySalary, idA).path("totalScore").asDouble()).isCloseTo(10.0, within(0.01));
        assertThat(itemOf(bySalary, idA).path("rank").asInt()).isEqualTo(1);

        // 只给加班权重 → C（加班 10 分）第一
        ApiResult byWlb = post(PATH + "/compare", token, Map.of(
                "offerIds", List.of(String.valueOf(idA), String.valueOf(idB), String.valueOf(idC)),
                "wlbWeight", 100)).assertSuccess();

        assertThat(itemOf(byWlb, idC).path("totalScore").asDouble()).isCloseTo(10.0, within(0.01));
        assertThat(itemOf(byWlb, idC).path("rank").asInt()).isEqualTo(1);
        assertThat(itemOf(byWlb, idA).path("rank").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("权重不必凑成 100：传 30/20 也能归一化")
    void weightsAreNormalized() {
        ApiResult result = post(PATH + "/compare", token, Map.of(
                "offerIds", List.of(String.valueOf(idA), String.valueOf(idB)),
                "salaryWeight", 30, "techWeight", 20)).assertSuccess();
        var data = result.data();

        // 30 : 20 → 0.6 : 0.4
        assertThat(data.path("weights").path("salary").asDouble()).isCloseTo(0.6, within(0.001));
        assertThat(data.path("weights").path("tech").asDouble()).isCloseTo(0.4, within(0.001));
        // A = 10*0.6 + 4*0.4 = 7.6；B = 6*0.6 + 8*0.4 = 6.8
        assertThat(itemOf(result, idA).path("totalScore").asDouble()).isCloseTo(7.6, within(0.01));
        assertThat(itemOf(result, idB).path("totalScore").asDouble()).isCloseTo(6.8, within(0.01));
    }

    @Test
    @DisplayName("★ 权重倾斜提醒：冠军不是最重那一维的最优时要点出来")
    void warnsWhenWinnerIsNotBestInDominantDimension() {
        // 造一个「薪资差一点、但技术碾压」的场景：
        // A：薪资 9 · 技术 10   B：薪资 10 · 技术 1
        // 权重 薪资 40 : 技术 20（其余 0）→ A 总分 6.0 夺冠，但薪资最优的是 B
        jdbc.update("UPDATE job_offer SET salary_score = 9, tech_score = 10 WHERE id = ?", idA);
        jdbc.update("UPDATE job_offer SET salary_score = 10, tech_score = 1 WHERE id = ?", idB);
        jdbc.update("UPDATE job_offer SET salary_score = 1, tech_score = 1 WHERE id = ?", idC);

        ApiResult result = post(PATH + "/compare", token, Map.of(
                "offerIds", List.of(String.valueOf(idA), String.valueOf(idB)),
                "salaryWeight", 40, "techWeight", 20)).assertSuccess();

        assertThat(itemOf(result, idA).path("rank").asInt())
                .as("A 靠技术拉分夺冠，但它不是薪资最优的那家").isEqualTo(1);

        String recommendation = result.data().path("recommendation").asText();
        assertThat(recommendation).contains("薪资包").contains("权重最重");
        assertThat(recommendation).contains("B 均衡公司");
    }

    @Test
    @DisplayName("各维度最优会回填到每家的 bestDimensions")
    void marksBestDimensionsPerOffer() {
        ApiResult result = post(PATH + "/compare", token, Map.of(
                "offerIds", List.of(String.valueOf(idA), String.valueOf(idB), String.valueOf(idC))))
                .assertSuccess();

        assertThat(itemOf(result, idA).path("bestDimensions").toString()).contains("salary");
        assertThat(itemOf(result, idB).path("bestDimensions").toString()).contains("tech");
        assertThat(itemOf(result, idC).path("bestDimensions").toString()).contains("wlb");
    }

    // ---------- 对比：边界 ----------

    @Test
    @DisplayName("对比边界：少于 2 个 / 超过 5 个 / 权重全 0 → 40001")
    void compareBoundaries() {
        post(PATH + "/compare", token, Map.of("offerIds", List.of(String.valueOf(idA))))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        post(PATH + "/compare", token, Map.of("offerIds", List.of(
                String.valueOf(idA), String.valueOf(idB), String.valueOf(idC),
                "1", "2", "3")))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        post(PATH + "/compare", token, Map.of(
                "offerIds", List.of(String.valueOf(idA), String.valueOf(idB)),
                "salaryWeight", 0, "techWeight", 0))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("对比里混进别人的 Offer → 404")
    void compareWithOthersOfferIsNotFound() {
        createAccount("t_other_cmp");
        String otherToken = loginRaw("/app/auth/login", "t_other_cmp", TEST_PASSWORD)
                .assertSuccess().data().path("accessToken").asText();
        Long otherOfferId = post(PATH, otherToken, Map.of(
                "companyName", "别人家的公司", "salaryScore", 5, "techScore", 5,
                "brandScore", 5, "wlbScore", 5, "commuteScore", 5)).assertSuccess().longId();

        post(PATH + "/compare", token, Map.of("offerIds", List.of(
                String.valueOf(idA), String.valueOf(otherOfferId))))
                .assertCode(ErrorCode.OFFER_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("对比里传重复 id 会去重（不足 2 个则报错）")
    void compareDeduplicatesIds() {
        post(PATH + "/compare", token, Map.of("offerIds", List.of(
                String.valueOf(idA), String.valueOf(idA))))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresAuth() {
        get(PATH, null).assertHttpStatus(401);
        post(PATH + "/compare", null, Map.of("offerIds", List.of("1", "2")))
                .assertHttpStatus(401);
    }
}
