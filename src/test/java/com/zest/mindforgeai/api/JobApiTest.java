package com.zest.mindforgeai.api;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 职位 JD：CRUD + 解析 + 归属校验 + 删除连带清理。
 *
 * <p>「删除连带清理」这条是测试套件第一次跑就抓出来的真 bug ——
 * 简历和职位删除时都没有清各自的子表（技能、匹配分析），
 * 而面试删除是清的。本项目所有表都没有外键，漏了就留孤儿数据。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("职位 JD")
class JobApiTest extends AbstractApiTest {

    private static final String SAMPLE_JD = """
            高级 Java 后端开发工程师

            任职要求
            1. 5 年以上 Java 后端开发经验
            2. 熟悉 MySQL、Redis、Kafka
            """;

    private String token;
    private long jobId;

    @BeforeEach
    void createOneJob() {
        token = loginAsUser();
        jobId = post("/app/jobs", token, Map.of(
                "title", "高级 Java 后端开发工程师",
                "companyName", "某某互联网公司",
                "location", "上海",
                "jobDescription", SAMPLE_JD)).assertSuccess().longId();
    }

    private void stubParse() throws Exception {
        when(aiServiceClient.parseJob(any())).thenReturn(new AiServiceClient.JobParseData(
                "高级 Java 后端岗，要求 5 年经验。",
                objectMapper.readTree("{\"title\":\"高级 Java 后端开发工程师\"}"),
                List.of(
                        new AiServiceClient.JobParseData.Skill("Java", "LANGUAGE", "ADVANCED", true),
                        new AiServiceClient.JobParseData.Skill("Kafka", "TOOL", "MEDIUM", true)),
                false));
    }

    // ---------- CRUD ----------

    @Test
    @DisplayName("创建后落库，初始状态 PENDING")
    void createPersists() {
        assertThat(jdbc.queryForObject(
                "SELECT parse_status FROM job WHERE id = ?", String.class, jobId))
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("职位名称为空 → 40001")
    void blankTitleIsRejected() {
        post("/app/jobs", token, Map.of("title", "", "jobDescription", "x"))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("JD 正文为空 → 40001")
    void blankDescriptionIsRejected() {
        post("/app/jobs", token, Map.of("title", "x", "jobDescription", ""))
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("列表分页 + camelCase")
    void listIsPaged() {
        ApiResult result = get("/app/jobs?page=1&pageSize=5", token).assertSuccess().assertCamelCase();
        assertThat(result.data().get("total").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("修改职位")
    void update() {
        put("/app/jobs/" + jobId, token, Map.of(
                "title", "改过的职位名",
                "companyName", "新公司",
                "jobDescription", "新的 JD 正文")).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT title FROM job WHERE id = ?", String.class, jobId))
                .isEqualTo("改过的职位名");
    }

    // ---------- 解析 ----------

    @Test
    @DisplayName("解析成功：状态转 SUCCESS，技能要求落库")
    void parsePersistsSkills() throws Exception {
        stubParse();

        post("/app/jobs/" + jobId + "/parse", token, null).assertSuccess().assertCamelCase();

        assertThat(jdbc.queryForObject("SELECT parse_status FROM job WHERE id = ?",
                String.class, jobId)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_skill WHERE job_id = ?",
                Integer.class, jobId)).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 解析会重写技能表（全删全插，不是追加）")
    void reparseRewritesSkills() throws Exception {
        stubParse();
        post("/app/jobs/" + jobId + "/parse", token, null).assertSuccess();
        post("/app/jobs/" + jobId + "/parse", token, null).assertSuccess();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_skill WHERE job_id = ?",
                Integer.class, jobId))
                .as("重复解析不能让技能翻倍").isEqualTo(2);
    }

    @Test
    @DisplayName("没有 JD 正文的职位不能解析 → 40001")
    void parseWithoutContentIsRejected() {
        // 直接造一条正文为空的职位（正常流程走不到，但接口得挡住）
        // ⚠️ raw_content 是 NOT NULL，只能置空串不能置 null
        jdbc.update("UPDATE job SET raw_content = '' WHERE id = ?", jobId);

        post("/app/jobs/" + jobId + "/parse", token, null)
                .assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    // ---------- 删除与归属 ----------

    @Test
    @DisplayName("★ 删除：连带物理清掉技能和匹配分析（测试套件抓出来的 bug）")
    void deleteCascadesSkillsAndAnalyses() throws Exception {
        stubParse();
        post("/app/jobs/" + jobId + "/parse", token, null).assertSuccess();
        // 造一条匹配分析记录，验证它也会被清掉
        // ⚠️ 这张表没有 updated_at 列（只有 created_at）
        jdbc.update("""
                INSERT INTO resume_job_analysis (id, user_id, resume_id, job_id, overall_score,
                                                 match_items_json, created_at)
                VALUES (?, ?, 999999, ?, 50.0, '[]', NOW())
                """, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), userId(USER_NAME), jobId);

        delete("/app/jobs/" + jobId, token).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE id = ? AND deleted = 0", Integer.class, jobId))
                .as("职位表走逻辑删除").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_skill WHERE job_id = ?", Integer.class, jobId))
                .as("技能表没有 deleted 列，必须物理删 —— 不删就是孤儿数据").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resume_job_analysis WHERE job_id = ?", Integer.class, jobId))
                .as("匹配分析引用着 jobId，职位没了它就没意义了").isZero();
    }

    @Test
    @DisplayName("别人的职位：404，且不能改、不能删")
    void otherUsersJobIsInaccessible() {
        createAccount("t_other_user");
        String otherToken = loginRaw("/app/auth/login", "t_other_user", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        get("/app/jobs/" + jobId, otherToken).assertHttpStatus(404);
        put("/app/jobs/" + jobId, otherToken, Map.of("title", "劫持", "jobDescription", "x"))
                .assertHttpStatus(404);
        delete("/app/jobs/" + jobId, otherToken).assertHttpStatus(404);
    }

    @Test
    @DisplayName("不存在的职位：404")
    void missingIsNotFound() {
        get("/app/jobs/99999999", token)
                .assertHttpStatus(404)
                .assertCode(ErrorCode.JOB_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("★ CRM 里把目标简历改成空串要真的解绑（updateById 会漏掉这个 NULL）")
    void crmCanUnbindResume() {
        long resumeId = uploadResume(token, "张伟-简历.txt", "张伟，5 年 Java 后端经验").assertSuccess().longId();

        put("/app/jobs/" + jobId + "/crm", token, Map.of("linkedResumeId", String.valueOf(resumeId)))
                .assertSuccess();
        assertThat(jdbc.queryForObject(
                "SELECT linked_resume_id FROM job WHERE id = ?", Long.class, jobId))
                .isEqualTo(resumeId);

        put("/app/jobs/" + jobId + "/crm", token, Map.of("linkedResumeId", "")).assertSuccess();

        assertThat(jdbc.queryForObject(
                "SELECT linked_resume_id FROM job WHERE id = ?", Long.class, jobId))
                .as("空串是「解除绑定」的约定写法，不是「不修改」").isNull();
    }
}
