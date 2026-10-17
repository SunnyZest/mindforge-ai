package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户端薪酬估算：{@code /api/app/salary/**}。
 *
 * <p>这个功能的测试重点不是「估得准不准」（没有真实样本，测不了），
 * 而是三件会真正伤到用户的事：
 * <ol>
 *   <li><b>置信度不能被模型牵着走</b> —— 城市/岗位/年限不在常见组合里时必须降档；</li>
 *   <li><b>缓存必须按参数走</b> —— 同参数不重复烧 token，改一个参数就必须重算；</li>
 *   <li><b>免责声明必须一直在</b> —— 它是这个功能唯一的安全垫。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-17
 */
@DisplayName("用户端-薪酬估算")
class SalaryEstimateApiTest extends AbstractApiTest {

    private String token;

    @BeforeEach
    void login() {
        token = loginAsUser();
    }

    // ------------------------------------------------------------ 主链路

    @Test
    @DisplayName("★ 估算：回显区间、置信度与免责声明，cached=false")
    void estimateReturnsRanges() {
        stubEstimate(25000, 32000, 42000, "HIGH");

        ApiResult res = post("/app/salary/estimate", token, body()).assertSuccess().assertCamelCase();

        assertThat(res.data().path("monthlyP50").asDouble()).isEqualTo(25000.0);
        assertThat(res.data().path("monthlyP75").asDouble()).isEqualTo(32000.0);
        assertThat(res.data().path("monthlyP90").asDouble()).isEqualTo(42000.0);
        assertThat(res.data().path("annualP50").asDouble()).isEqualTo(350000.0);
        assertThat(res.data().path("monthsPerYear").asInt()).isEqualTo(14);
        assertThat(res.data().path("confidence").asText()).isEqualTo("HIGH");
        assertThat(res.data().path("cached").asBoolean()).isFalse();
        assertThat(res.data().path("basis").size()).isEqualTo(1);

        // 免责声明是功能的安全垫，任何一次返回都不能缺
        assertThat(res.data().path("caveat").asText())
                .contains("不是真实薪酬样本")
                .contains("以对方给出的书面数字为准");
    }

    @Test
    @DisplayName("估算结果落库，参数指纹与免责声明都写进去了")
    void estimatePersistsRow() {
        stubEstimate(25000, 32000, 42000, "HIGH");
        post("/app/salary/estimate", token, body()).assertSuccess();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, request_key, city, position, years_of_experience,"
                        + " monthly_p50, confidence, caveat FROM salary_estimate");
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId(USER_NAME));
        assertThat(row.get("request_key").toString()).hasSize(64);
        assertThat(row.get("city")).isEqualTo("杭州");
        assertThat(((Number) row.get("monthly_p50")).doubleValue()).isEqualTo(25000.0);
        assertThat(row.get("confidence")).isEqualTo("HIGH");
        assertThat(row.get("caveat").toString()).contains("不是真实薪酬样本");
    }

    // ------------------------------------------------------------ 缓存

    @Test
    @DisplayName("★ 同参数 7 天内命中缓存：第二次不再调模型，库中只有一条")
    void sameParamsHitCache() {
        stubEstimate(25000, 32000, 42000, "HIGH");

        post("/app/salary/estimate", token, body()).assertSuccess();
        ApiResult second = post("/app/salary/estimate", token, body()).assertSuccess();

        assertThat(second.data().path("cached").asBoolean()).isTrue();
        assertThat(second.data().path("monthlyP50").asDouble()).isEqualTo(25000.0);
        assertThat(countRows()).isEqualTo(1);
        verify(aiServiceClient, times(1)).estimateSalary(any());
    }

    @Test
    @DisplayName("★ 技能顺序不同不算新参数（指纹里先排序）")
    void skillOrderDoesNotBreakCache() {
        stubEstimate(25000, 32000, 42000, "HIGH");

        Map<String, Object> first = body();
        first.put("skills", List.of("Java", "MySQL", "Redis"));
        Map<String, Object> second = body();
        second.put("skills", List.of("Redis", "Java", "MySQL"));

        post("/app/salary/estimate", token, first).assertSuccess();
        ApiResult res = post("/app/salary/estimate", token, second).assertSuccess();

        assertThat(res.data().path("cached").asBoolean()).isTrue();
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 改城市就要重算（缓存只认完全相同的参数）")
    void differentCityRecalculates() {
        stubEstimate(25000, 32000, 42000, "HIGH");

        post("/app/salary/estimate", token, body()).assertSuccess();

        Map<String, Object> other = body();
        other.put("city", "成都");
        ApiResult res = post("/app/salary/estimate", token, other).assertSuccess();

        assertThat(res.data().path("cached").asBoolean()).isFalse();
        assertThat(countRows()).isEqualTo(2);
        verify(aiServiceClient, times(2)).estimateSalary(any());
    }

    @Test
    @DisplayName("★ 改当前年包也要重算 —— 它会传给模型，少放一个字段就会返回按旧输入算的数")
    void differentCurrentSalaryRecalculates() {
        stubEstimate(25000, 32000, 42000, "HIGH");

        post("/app/salary/estimate", token, body()).assertSuccess();

        Map<String, Object> raised = body();
        raised.put("currentAnnualSalary", 400000);
        ApiResult res = post("/app/salary/estimate", token, raised).assertSuccess();

        assertThat(res.data().path("cached").asBoolean()).isFalse();
        assertThat(countRows()).isEqualTo(2);
    }

    // ------------------------------------------------------------ 置信度钳制

    @Test
    @DisplayName("★ 城市不认识 → 模型说 HIGH 也压到 MEDIUM")
    void unknownCityIsCappedToMedium() {
        stubEstimate(20000, 25000, 30000, "HIGH");

        Map<String, Object> req = body();
        req.put("city", "某某镇");
        ApiResult res = post("/app/salary/estimate", token, req).assertSuccess();

        assertThat(res.data().path("confidence").asText()).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("★ 岗位不认识 → 压到 MEDIUM")
    void unknownPositionIsCappedToMedium() {
        stubEstimate(20000, 25000, 30000, "HIGH");

        Map<String, Object> req = body();
        req.put("position", "星际殖民者");
        ApiResult res = post("/app/salary/estimate", token, req).assertSuccess();

        assertThat(res.data().path("confidence").asText()).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("★ 城市和岗位都不认识 → 直接 LOW")
    void bothUnknownIsLow() {
        stubEstimate(20000, 25000, 30000, "HIGH");

        Map<String, Object> req = body();
        req.put("city", "某某镇");
        req.put("position", "星际殖民者");
        ApiResult res = post("/app/salary/estimate", token, req).assertSuccess();

        assertThat(res.data().path("confidence").asText()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("★ 规则只往下压，不往上抬：模型说 LOW 就还是 LOW")
    void rulesNeverRaiseConfidence() {
        stubEstimate(25000, 32000, 42000, "LOW");

        ApiResult res = post("/app/salary/estimate", token, body()).assertSuccess();
        assertThat(res.data().path("confidence").asText()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("应届（0 年）与高阶（>15 年）都压到 MEDIUM")
    void extremeYearsAreCappedToMedium() {
        stubEstimate(25000, 32000, 42000, "HIGH");

        Map<String, Object> fresh = body();
        fresh.put("yearsOfExperience", 0);
        assertThat(post("/app/salary/estimate", token, fresh).assertSuccess()
                .data().path("confidence").asText()).isEqualTo("MEDIUM");

        Map<String, Object> senior = body();
        senior.put("yearsOfExperience", 20);
        assertThat(post("/app/salary/estimate", token, senior).assertSuccess()
                .data().path("confidence").asText()).isEqualTo("MEDIUM");
    }

    // ------------------------------------------------------------ 结果自检

    @Test
    @DisplayName("★ 分位顺序被模型写反 → 按升序修正，不报错")
    void reversedPercentilesAreSorted() {
        stubEstimate(42000, 32000, 25000, "HIGH");

        ApiResult res = post("/app/salary/estimate", token, body()).assertSuccess();

        assertThat(res.data().path("monthlyP50").asDouble()).isEqualTo(25000.0);
        assertThat(res.data().path("monthlyP90").asDouble()).isEqualTo(42000.0);
    }

    @Test
    @DisplayName("月数超出 12~16 → 置空（多半是模型把月薪年薪搞混了）")
    void absurdMonthsBecomesNull() {
        when(aiServiceClient.estimateSalary(any())).thenReturn(new AiServiceClient.SalaryEstimateData(
                25000.0, 32000.0, 42000.0, 350000.0, 448000.0, 588000.0,
                24, "HIGH", List.of("依据"), List.of()));

        ApiResult res = post("/app/salary/estimate", token, body()).assertSuccess();
        assertThat(res.data().path("monthsPerYear").isNull()).isTrue();
    }

    @Test
    @DisplayName("★ 分位缺一个 → 502 系列错误（不拿别的数硬补）")
    void incompleteRangesFail() {
        when(aiServiceClient.estimateSalary(any())).thenReturn(new AiServiceClient.SalaryEstimateData(
                25000.0, null, 42000.0, 350000.0, 448000.0, 588000.0,
                14, "HIGH", List.of(), List.of()));

        post("/app/salary/estimate", token, body())
                .assertCode(ErrorCode.AI_SERVICE_ERROR.getCode());
        assertThat(countRows()).as("失败不该留下半条记录").isZero();
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("参数校验：城市为空 / 年限越界 / 技能过多 → 40001")
    void validationRejectsBadInput() {
        Map<String, Object> noCity = body();
        noCity.put("city", "  ");
        post("/app/salary/estimate", token, noCity)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        Map<String, Object> badYears = body();
        badYears.put("yearsOfExperience", 41);
        post("/app/salary/estimate", token, badYears)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());

        List<String> tooManySkills = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooManySkills.add("skill-" + i);
        }
        Map<String, Object> manySkills = body();
        manySkills.put("skills", tooManySkills);
        post("/app/salary/estimate", token, manySkills)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresLogin() {
        post("/app/salary/estimate", null, body()).assertCode(ErrorCode.UNAUTHORIZED.getCode());
        get("/app/salary/estimates", null).assertCode(ErrorCode.UNAUTHORIZED.getCode());
    }

    // ------------------------------------------------------------ 历史

    @Test
    @DisplayName("★ 历史：只返回自己的记录，按时间倒序，分页生效")
    void historyIsScopedAndPaginated() {
        stubEstimate(25000, 32000, 42000, "HIGH");
        post("/app/salary/estimate", token, body()).assertSuccess();

        Map<String, Object> second = body();
        second.put("city", "成都");
        post("/app/salary/estimate", token, second).assertSuccess();

        ApiResult res = get("/app/salary/estimates?page=1&pageSize=1", token)
                .assertSuccess().assertCamelCase();

        assertThat(res.data().path("total").asLong()).isEqualTo(2);
        assertThat(res.data().path("records").size()).isEqualTo(1);
        // 倒序：最后算的成都排在第一条
        assertThat(res.data().path("records").get(0).path("city").asText()).isEqualTo("成都");
        assertThat(res.data().path("records").get(0).path("cached").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★ 历史不串号：别人的记录看不到")
    void historyIsolatedBetweenUsers() {
        stubEstimate(25000, 32000, 42000, "HIGH");
        post("/app/salary/estimate", token, body()).assertSuccess();

        createAccount("salary_other");
        String otherToken = loginRaw("/app/auth/login", "salary_other", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        ApiResult res = get("/app/salary/estimates", otherToken).assertSuccess();
        assertThat(res.data().path("total").asLong()).isZero();
    }

    // ------------------------------------------------------------ 辅助

    private Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("city", "杭州");
        body.put("position", "Java 后端开发");
        body.put("yearsOfExperience", 3);
        body.put("education", "本科");
        body.put("companyType", "大厂");
        body.put("skills", List.of("Java", "MySQL"));
        return body;
    }

    /** 年包按「月薪 × 14」推算，与模型的常见口径一致。 */
    private void stubEstimate(double p50, double p75, double p90, String confidence) {
        when(aiServiceClient.estimateSalary(any())).thenReturn(new AiServiceClient.SalaryEstimateData(
                p50, p75, p90, p50 * 14, p75 * 14, p90 * 14, 14, confidence,
                List.of("一线城市 + 3 年经验"),
                List.of("掌握 Flink 可上浮")));
    }

    private int countRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM salary_estimate", Integer.class);
    }
}
