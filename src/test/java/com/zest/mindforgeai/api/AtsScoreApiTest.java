package com.zest.mindforgeai.api;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ATS 关键词跑分。
 *
 * <p>纯本地算法，所以测试重点在<b>算法边界</b>而不是链路：
 * 词边界（Java 不能命中 JavaScript）、别名（K8s ↔ Kubernetes）、
 * 必须项加权、命中来源区分（技能表 vs 正文）。
 *
 * @author crx
 * @since 2026-10-14
 */
@DisplayName("ATS 关键词跑分")
class AtsScoreApiTest extends AbstractApiTest {

    /** 简历正文：故意让 JavaScript 只出现在这里（不进技能表），用来验证 TEXT 命中 */
    private static final String RESUME_TEXT = """
            张三 | Java 后端工程师
            技能：Java、MySQL、Redis、Spring Boot、K8s
            项目经历：用 Spring Boot 重构订单系统，MySQL 分表 + Redis 缓存，K8s 部署
            其他：用 JavaScript 写过内部管理页
            """;

    private Long resumeId;
    private Long jobId;
    private String token;

    @BeforeEach
    void createFixture() {
        token = loginAsUser();
        Long uid = userId(USER_NAME);

        resumeId = IdWorker.getId();
        jdbc.update("""
                INSERT INTO resume (id, user_id, name, raw_content, parse_status, status, deleted,
                                    created_at, updated_at)
                VALUES (?, ?, 'ATS 测试简历', ?, 'SUCCESS', 1, 0, NOW(), NOW())
                """, resumeId, uid, RESUME_TEXT);

        jdbc.update("""
                INSERT INTO resume_skill (id, resume_id, skill_name, skill_type, proficiency, created_at)
                VALUES (?, ?, 'Java', 'LANGUAGE', '精通', NOW()),
                       (?, ?, 'MySQL', 'DATABASE', '熟练', NOW()),
                       (?, ?, 'Redis', 'DATABASE', '熟练', NOW()),
                       (?, ?, 'Spring Boot', 'FRAMEWORK', '熟练', NOW()),
                       (?, ?, 'K8s', 'TOOL', '熟练', NOW())
                """, IdWorker.getId(), resumeId, IdWorker.getId(), resumeId, IdWorker.getId(), resumeId,
                IdWorker.getId(), resumeId, IdWorker.getId(), resumeId);

        jobId = IdWorker.getId();
        jdbc.update("""
                INSERT INTO job (id, user_id, title, raw_content, parse_status, status, deleted,
                                 created_at, updated_at)
                VALUES (?, ?, 'Java 后端工程师', '任职要求：Java、MySQL、Kubernetes、分库分表', 'SUCCESS', 1, 0, NOW(), NOW())
                """, jobId, uid);

        // 必须项 4 个（权重 2×4=8）+ 加分项 3 个（权重 3）→ 总权重 11
        insertJobSkill("Java", true);
        insertJobSkill("MySQL", true);
        insertJobSkill("Kubernetes", true);
        insertJobSkill("分库分表", true);
        insertJobSkill("Redis", false);
        insertJobSkill("JavaScript", false);
        insertJobSkill("消息队列", false);
    }

    private void insertJobSkill(String name, boolean required) {
        jdbc.update("""
                INSERT INTO job_skill (id, job_id, skill_name, required, created_at)
                VALUES (?, ?, ?, ?, NOW())
                """, IdWorker.getId(), jobId, name, required ? 1 : 0);
    }

    private ApiResult score() {
        return get("/app/resumes/" + resumeId + "/ats-score?jobId=" + jobId, token);
    }

    // ---------- 核心算法 ----------

    @Test
    @DisplayName("★ 加权覆盖率：必须项 2 倍权重，缺失的必须项进 missingRequired")
    void scoresWithRequiredWeighting() {
        ApiResult result = score().assertSuccess().assertCamelCase();
        var data = result.data();

        // 命中：Java(2) + MySQL(2) + Kubernetes(2) + Redis(1) + JavaScript(1) = 8
        // 总权重：必须项 4×2 + 加分项 3×1 = 11 → 8/11 = 72.7
        assertThat(data.path("score").asDouble()).isCloseTo(72.7, within(0.05));
        assertThat(data.path("level").asText()).isEqualTo("MEDIUM");
        assertThat(data.path("totalKeywords").asInt()).isEqualTo(7);
        assertThat(data.path("matchedKeywords").asInt()).isEqualTo(5);
        assertThat(data.path("requiredTotal").asInt()).isEqualTo(4);
        assertThat(data.path("requiredMatched").asInt()).isEqualTo(3);

        var missing = data.path("missingRequired");
        assertThat(missing).hasSize(1);
        assertThat(missing.get(0).path("keyword").asText()).isEqualTo("分库分表");

        assertThat(data.path("summary").asText()).contains("命中 5 个").contains("3/4");
        assertThat(data.path("disclaimer").asText()).contains("关键词");
    }

    @Test
    @DisplayName("★ 词边界：Java 不能命中 JavaScript（否则分数虚高，用户拿着虚高的分去投简历）")
    void javaDoesNotMatchJavaScript() {
        // 把简历换成「只有 JavaScript、没有 Java」，并清掉技能表
        jdbc.update("UPDATE resume SET raw_content = ? WHERE id = ?",
                "前端工程师，熟悉 JavaScript 和 Vue", resumeId);
        jdbc.update("DELETE FROM resume_skill WHERE resume_id = ?", resumeId);

        ApiResult result = score().assertSuccess();
        var keywords = result.data().path("keywords");

        for (var keyword : keywords) {
            String name = keyword.path("keyword").asText();
            if ("Java".equals(name)) {
                assertThat(keyword.path("hit").asBoolean())
                        .as("Java 不该在 JavaScript 里命中").isFalse();
            }
            if ("JavaScript".equals(name)) {
                assertThat(keyword.path("hit").asBoolean()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("★ 别名：JD 写 Kubernetes、简历写 K8s，算命中")
    void aliasK8sMatchesKubernetes() {
        var keywords = score().assertSuccess().data().path("keywords");

        var kubernetes = findKeyword(keywords, "Kubernetes");
        assertThat(kubernetes.path("hit").asBoolean()).as("K8s 应等价于 Kubernetes").isTrue();
        assertThat(kubernetes.path("hitSource").asText()).isEqualTo("SKILL");
        assertThat(kubernetes.path("proficiency").asText()).isEqualTo("熟练");
    }

    @Test
    @DisplayName("命中来源区分：技能表里有=SKILL，只在正文出现=TEXT")
    void distinguishesHitSource() {
        var keywords = score().assertSuccess().data().path("keywords");

        var redis = findKeyword(keywords, "Redis");
        assertThat(redis.path("hitSource").asText()).isEqualTo("SKILL");

        // JavaScript 只在正文里（技能表没有），所以是 TEXT 命中
        var js = findKeyword(keywords, "JavaScript");
        assertThat(js.path("hitSource").asText()).isEqualTo("TEXT");
        assertThat(js.path("occurrences").asInt()).isGreaterThan(0);
        assertThat(js.path("proficiency").isMissingNode() || js.path("proficiency").isNull()).isTrue();
    }

    @Test
    @DisplayName("建议：缺必须项时说清是哪个；只在正文出现过的提示补进技能栏")
    void adviceMentionsGaps() {
        var advice = score().assertSuccess().data().path("advice");
        String joined = advice.toString();

        assertThat(joined).contains("分库分表");
        assertThat(joined).contains("JavaScript").contains("技能栏");
    }

    @Test
    @DisplayName("必须项全中时不提缺口，只提示加分项")
    void adviceWhenAllRequiredCovered() {
        // 把「分库分表」从 JD 要求里去掉 → 必须项变成 3 个且全部命中
        jdbc.update("DELETE FROM job_skill WHERE job_id = ? AND skill_name = '分库分表'", jobId);

        var data = score().assertSuccess().data();
        assertThat(data.path("requiredMatched").asInt())
                .isEqualTo(data.path("requiredTotal").asInt());
        assertThat(data.path("missingRequired")).isEmpty();
        assertThat(data.path("advice").toString()).contains("必须项全部覆盖");
    }

    // ---------- 边界与鉴权 ----------

    @Test
    @DisplayName("JD 没有技能项（没解析过）→ 40001")
    void jobWithoutSkillsIsRejected() {
        jdbc.update("DELETE FROM job_skill WHERE job_id = ?", jobId);
        score().assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("别人的简历 → 404（不区分「不存在」和「不是你的」）")
    void otherUsersResumeIsNotFound() {
        createAccount("t_other_ats");
        String otherToken = loginRaw("/app/auth/login", "t_other_ats", TEST_PASSWORD).assertSuccess()
                .data().path("accessToken").asText();

        get("/app/resumes/" + resumeId + "/ats-score?jobId=" + jobId, otherToken)
                .assertCode(ErrorCode.RESUME_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("未登录 → 401")
    void requiresAuth() {
        get("/app/resumes/" + resumeId + "/ats-score?jobId=" + jobId, null)
                .assertHttpStatus(401);
    }

    private com.fasterxml.jackson.databind.JsonNode findKeyword(
            com.fasterxml.jackson.databind.JsonNode keywords, String name) {
        for (var keyword : keywords) {
            if (name.equals(keyword.path("keyword").asText())) {
                return keyword;
            }
        }
        throw new AssertionError("关键词列表里没有 " + name);
    }
}
