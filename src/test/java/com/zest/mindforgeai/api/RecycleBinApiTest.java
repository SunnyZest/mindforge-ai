package com.zest.mindforgeai.api;

import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import com.zest.mindforgeai.task.FilePurgeTask;
import com.zest.mindforgeai.task.RecycleBinCleanupTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired
    private FilePurgeTask filePurgeTask;

    @Autowired
    private RecycleBinCleanupTask recycleBinCleanupTask;

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

    private String createJob(String token, String title) {
        return post("/app/jobs", token, Map.of(
                "title", title,
                "companyName", "某公司",
                "jobDescription", "负责后端开发")).assertSuccess().id();
    }

    /** 直接插 job_skill —— 该表没有 deleted 列，删除职位时是物理删 */
    private void insertSkill(Long id, String jobId, String name) {
        jdbc.update("INSERT INTO job_skill (id, job_id, skill_name, required, created_at) "
                        + "VALUES (?, ?, ?, ?, NOW())",
                id, Long.valueOf(jobId), name, 1);
    }

    private int countSkills(String jobId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM job_skill WHERE job_id = ?",
                Integer.class, Long.valueOf(jobId));
        return n == null ? 0 : n;
    }

    /** 按原资源 id 找回收站记录 id */
    private String findBinIdByResource(String token, String resourceId) {
        ApiResult r = get(BASE + "?page=1&pageSize=50", token).assertSuccess();
        for (var node : r.data().get("records")) {
            if (resourceId.equals(node.path("resourceId").asText())) {
                return node.path("id").asText();
            }
        }
        throw new AssertionError("回收站里找不到 resourceId=" + resourceId);
    }

    /** 统计某面试下的子表行数（表名是测试内常量，不是外部输入） */
    private int countByInterview(String table, long interviewId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE interview_id = ?",
                Integer.class, interviewId);
        return n == null ? 0 : n;
    }

    /** 按某个外键列统计行数（表名/列名是测试内常量，不是外部输入） */
    private int countBy(String table, String column, long value) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return n == null ? 0 : n;
    }

    /** chat_source 没有 conversation_id，只能通过 message_id 关联过去 */
    private int countSources(long conversationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_source s JOIN chat_message m ON m.id = s.message_id "
                        + "WHERE m.conversation_id = ?", Integer.class, conversationId);
        return n == null ? 0 : n;
    }

    /** 某知识库下仍处于逻辑删除状态的文档数（0 表示都恢复了） */
    private int countDeletedDocuments(long knowledgeBaseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM document WHERE knowledge_base_id = ? AND deleted = 1",
                Integer.class, knowledgeBaseId);
        return n == null ? 0 : n;
    }

    /** 逻辑删除标记（表名是测试内常量） */
    private int deletedFlag(String table, long id) {
        Integer n = jdbc.queryForObject(
                "SELECT deleted FROM " + table + " WHERE id = ?", Integer.class, id);
        return n == null ? -1 : n;
    }

    /** sys_file 的 deleted_at —— 恢复后必须是 null，否则清理任务会把 OSS 对象真删了 */
    private java.time.LocalDateTime deletedAt(String table, long id) {
        return jdbc.queryForObject(
                "SELECT deleted_at FROM " + table + " WHERE id = ?", java.time.LocalDateTime.class, id);
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
    @DisplayName("职位删除后进回收站，恢复时技能一起回来（二期 payload 快照）")
    void restoreJobBringsSkillsBack() {
        String token = loginAsUser();
        String jobId = createJob(token, "字节跳动");

        // 手工插技能，不走 AI 解析（解析要 3 分钟，测试等不起）
        insertSkill(900001L, jobId, "Java");
        insertSkill(900002L, jobId, "Spring");
        assertThat(countSkills(jobId)).isEqualTo(2);

        delete("/app/jobs/" + jobId, token).assertSuccess();

        // ★ 技能是被物理删的 —— 这正是二期要解决的那部分：
        //   只把 job.deleted 改回 0 的话，恢复出来会是个没有技能要求的空职位
        assertThat(countSkills(jobId)).isZero();

        // 回收站记录里存了快照
        String binId = findBinIdByResource(token, jobId);
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM recycle_bin WHERE id = ?", String.class, Long.valueOf(binId));
        assertThat(payload).contains("Java").contains("Spring");

        post(BASE + "/" + binId + "/restore", token, Map.of()).assertSuccess();

        // 主表回来了，技能也一起回来了
        get("/app/jobs/" + jobId, token).assertSuccess();
        assertThat(countSkills(jobId)).isEqualTo(2);
    }

    @Test
    @DisplayName("面试删除后恢复，题目与报告一起回来（二期 payload 快照）")
    void restoreInterviewBringsQuestionsBack() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long interviewId = 910001L;

        // 直接建数据，不走「出题」接口（那要调 AI，测试等不起）
        jdbc.update("INSERT INTO interview (id, user_id, title, interview_type, question_count) "
                + "VALUES (?, ?, ?, ?, ?)", interviewId, userId, "测试面试", "TECHNICAL", 2);
        jdbc.update("INSERT INTO interview_question (id, interview_id, question_no, question) "
                + "VALUES (?, ?, ?, ?)", 910002L, interviewId, 1, "请介绍 JVM 内存模型");
        jdbc.update("INSERT INTO interview_question (id, interview_id, question_no, question) "
                + "VALUES (?, ?, ?, ?)", 910003L, interviewId, 2, "Spring 事务传播行为有哪些");
        jdbc.update("INSERT INTO interview_report (id, interview_id) VALUES (?, ?)",
                910004L, interviewId);

        delete("/app/interviews/" + interviewId, token).assertSuccess();

        // ★ 题目和报告是被物理删的 —— 只恢复主表的话，这就是一个空面试
        assertThat(countByInterview("interview_question", interviewId)).isZero();
        assertThat(countByInterview("interview_report", interviewId)).isZero();

        String binId = findBinIdByResource(token, String.valueOf(interviewId));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM recycle_bin WHERE id = ?", String.class, Long.valueOf(binId));
        assertThat(payload).contains("JVM 内存模型");

        post(BASE + "/" + binId + "/restore", token, Map.of()).assertSuccess();

        get("/app/interviews/" + interviewId, token).assertSuccess();
        assertThat(countByInterview("interview_question", interviewId)).isEqualTo(2);
        assertThat(countByInterview("interview_report", interviewId)).isEqualTo(1);
    }

    @Test
    @DisplayName("对话删除后恢复，消息与引用一起回来（message_id 要重映射）")
    void restoreConversationBringsMessagesBack() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long convId = 920001L;

        jdbc.update("INSERT INTO conversation (id, user_id, title, status) VALUES (?, ?, ?, ?)",
                convId, userId, "测试对话", 1);
        jdbc.update("INSERT INTO chat_message (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
                920002L, convId, "USER", "什么是 JVM");
        jdbc.update("INSERT INTO chat_message (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
                920003L, convId, "ASSISTANT", "JVM 是 Java 虚拟机");
        jdbc.update("INSERT INTO chat_source (id, message_id, document_id, chunk_id) VALUES (?, ?, ?, ?)",
                920004L, 920002L, 1L, 1L);
        jdbc.update("INSERT INTO chat_source (id, message_id, document_id, chunk_id) VALUES (?, ?, ?, ?)",
                920005L, 920003L, 1L, 1L);

        delete("/app/conversations/" + convId, token).assertSuccess();

        assertThat(countBy("chat_message", "conversation_id", convId)).isZero();
        assertThat(countSources(convId)).isZero();

        String binId = findBinIdByResource(token, String.valueOf(convId));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM recycle_bin WHERE id = ?", String.class, Long.valueOf(binId));
        assertThat(payload).contains("什么是 JVM");

        post(BASE + "/" + binId + "/restore", token, Map.of()).assertSuccess();

        assertThat(countBy("chat_message", "conversation_id", convId)).isEqualTo(2);
        assertThat(countSources(convId)).isEqualTo(2);

        // ★ 关键断言：引用必须挂在「新」消息上。
        //   如果恢复时沿用了旧 message_id，这里会 > 0 —— 那才是真正的坑
        Integer dangling = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_source s LEFT JOIN chat_message m ON m.id = s.message_id "
                        + "WHERE m.id IS NULL", Integer.class);
        assertThat(dangling).isZero();
    }

    @Test
    @DisplayName("关联数据没保存下来时，恢复返回 complete=false 并带警告")
    void restoreWithoutPayloadReturnsWarning() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long jobId = 930001L;

        // 手工造「快照采集失败」的历史数据：已逻辑删的职位 + 一条 payload 为 NULL 的回收站记录。
        // 现实里这发生在 capturePayload 抛异常时（那时只打了 log.error，删除照常进行）。
        jdbc.update("INSERT INTO job (id, user_id, title, raw_content, deleted) VALUES (?, ?, ?, ?, 1)",
                jobId, userId, "残缺职位", "职位描述正文");
        jdbc.update("INSERT INTO recycle_bin (id, user_id, resource_type, resource_id, title, "
                        + "payload_json, deleted_at, expire_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))",
                930002L, userId, "JOB", jobId, "残缺职位");

        ApiResult r = post(BASE + "/930002/restore", token, Map.of()).assertSuccess();

        // ★ 关键：不能只返回成功 —— 用户会以为「都回来了」
        assertThat(r.data().path("complete").asBoolean()).isFalse();
        assertThat(r.data().path("warning").asText()).contains("关联数据");

        // 主表确实回来了
        get("/app/jobs/" + jobId, token).assertSuccess();
    }

    @Test
    @DisplayName("简历删除后恢复，技能一起回来（三期 payload 快照）")
    void restoreResumeBringsSkillsBack() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long resumeId = 940001L;

        jdbc.update("INSERT INTO resume (id, user_id, name) VALUES (?, ?, ?)",
                resumeId, userId, "测试简历");
        jdbc.update("INSERT INTO resume_skill (id, resume_id, skill_name) VALUES (?, ?, ?)",
                940002L, resumeId, "Java");
        jdbc.update("INSERT INTO resume_skill (id, resume_id, skill_name) VALUES (?, ?, ?)",
                940003L, resumeId, "MySQL");
        assertThat(countBy("resume_skill", "resume_id", resumeId)).isEqualTo(2);

        delete("/app/resumes/" + resumeId, token).assertSuccess();

        // 技能是被物理删的 —— 只恢复主表的话，简历就成了「没有任何技能」
        assertThat(countBy("resume_skill", "resume_id", resumeId)).isZero();

        String binId = findBinIdByResource(token, String.valueOf(resumeId));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM recycle_bin WHERE id = ?", String.class, Long.valueOf(binId));
        assertThat(payload).contains("Java").contains("MySQL");

        post(BASE + "/" + binId + "/restore", token, Map.of()).assertSuccess();

        get("/app/resumes/" + resumeId, token).assertSuccess();
        assertThat(countBy("resume_skill", "resume_id", resumeId)).isEqualTo(2);
    }

    @Test
    @DisplayName("知识库删除后恢复，里面的文档一起回来（三期 payload 快照）")
    void restoreKnowledgeBaseBringsDocumentsBack() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long kbId = 950001L;

        jdbc.update("INSERT INTO knowledge_base (id, user_id, name) VALUES (?, ?, ?)",
                kbId, userId, "测试知识库");
        jdbc.update("INSERT INTO document (id, user_id, knowledge_base_id, file_name, file_type) "
                + "VALUES (?, ?, ?, ?, ?)", 950002L, userId, kbId, "第一篇.pdf", "PDF");
        jdbc.update("INSERT INTO document (id, user_id, knowledge_base_id, file_name, file_type) "
                + "VALUES (?, ?, ?, ?, ?)", 950003L, userId, kbId, "第二篇.pdf", "PDF");

        delete("/app/knowledge-bases/" + kbId, token).assertSuccess();

        // 文档是逻辑删的（deleted = 1），不是物理删
        assertThat(countDeletedDocuments(kbId)).isEqualTo(2);

        String binId = findBinIdByResource(token, String.valueOf(kbId));
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM recycle_bin WHERE id = ?", String.class, Long.valueOf(binId));
        assertThat(payload).contains("950002").contains("950003");

        post(BASE + "/" + binId + "/restore", token, Map.of()).assertSuccess();

        get("/app/knowledge-bases/" + kbId, token).assertSuccess();
        // 级联删掉的文档全部回来了
        assertThat(countDeletedDocuments(kbId)).isZero();
    }

    @Test
    @DisplayName("文档删除后恢复，sys_file 一起还原（三期）")
    void restoreDocumentRestoresFile() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long kbId = 960001L;
        long fileId = 960002L;
        long docId = 960003L;

        jdbc.update("INSERT INTO knowledge_base (id, user_id, name) VALUES (?, ?, ?)",
                kbId, userId, "测试库");
        jdbc.update("INSERT INTO sys_file (id, user_id, original_name, object_key, bucket_name, "
                        + "file_size, biz_type, storage_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                fileId, userId, "测试.pdf", "obj/key", "bucket", 1024, "document", "OSS");
        jdbc.update("INSERT INTO document (id, user_id, knowledge_base_id, file_id, file_name, file_type) "
                + "VALUES (?, ?, ?, ?, ?, ?)", docId, userId, kbId, fileId, "测试.pdf", "PDF");

        delete("/app/documents/" + docId, token).assertSuccess();

        // 文档逻辑删、sys_file 软删（OSS 对象保留 30 天）
        assertThat(deletedFlag("document", docId)).isEqualTo(1);
        assertThat(deletedFlag("sys_file", fileId)).isEqualTo(1);

        String binId = findBinIdByResource(token, String.valueOf(docId));
        post(BASE + "/" + binId + "/restore", token, Map.of()).assertSuccess();

        get("/app/documents/" + docId, token).assertSuccess();
        assertThat(deletedFlag("document", docId)).isZero();
        // ★ sys_file 的 deleted_at 也要清掉，否则 FilePurgeTask 会把 OSS 对象真删了
        assertThat(deletedFlag("sys_file", fileId)).isZero();
        assertThat(deletedAt("sys_file", fileId)).isNull();
    }

    @Test
    @DisplayName("回收站里未过期的文件，清理任务不会删（OSS 对象保住）")
    void purgeSkipsFileStillInRecycleBin() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long kbId = 970001L;
        long fileId = 970002L;
        long docId = 970003L;

        jdbc.update("INSERT INTO knowledge_base (id, user_id, name) VALUES (?, ?, ?)",
                kbId, userId, "清理测试库");
        // 文件已过期（40 天前删除，保留期 30 天）
        jdbc.update("INSERT INTO sys_file (id, user_id, original_name, object_key, bucket_name, "
                        + "file_size, biz_type, storage_type, deleted, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, DATE_SUB(NOW(), INTERVAL 40 DAY))",
                fileId, userId, "要保住的.pdf", "obj/protected", "bucket", 1024, "document", "OSS");
        jdbc.update("INSERT INTO document (id, user_id, knowledge_base_id, file_id, file_name, file_type, deleted) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1)", docId, userId, kbId, fileId, "要保住的.pdf", "PDF");
        // 但回收站记录还没过期 —— 用户随时可能恢复
        jdbc.update("INSERT INTO recycle_bin (id, user_id, resource_type, resource_id, title, "
                        + "deleted_at, expire_at) VALUES (?, ?, 'DOCUMENT', ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 10 DAY))",
                970004L, userId, docId, "要保住的.pdf");

        filePurgeTask.purgeExpiredFiles();

        // ★ 文件没被物理删，而且 deleted_at 被续期到今天（否则它会一直占满清理批次）
        assertThat(deletedFlag("sys_file", fileId)).isEqualTo(1);
        assertThat(deletedAt("sys_file", fileId)).isNotNull();
    }

    @Test
    @DisplayName("回收站记录过期后：先归档、再物理删业务行、最后删记录")
    void cleanupArchivesThenPurges() {
        String token = loginAsUser();
        long userId = Long.parseLong(get("/app/auth/me", token).assertSuccess().id());
        long jobId = 980001L;

        // 一个已逻辑删的职位 + 一条已过期的回收站记录
        jdbc.update("INSERT INTO job (id, user_id, title, raw_content, deleted) VALUES (?, ?, ?, ?, 1)",
                jobId, userId, "过期待归档", "职位正文");
        jdbc.update("INSERT INTO recycle_bin (id, user_id, resource_type, resource_id, title, "
                        + "deleted_at, expire_at) VALUES (?, ?, 'JOB', ?, ?, "
                        + "DATE_SUB(NOW(), INTERVAL 40 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY))",
                980002L, userId, jobId, "过期待归档");

        recycleBinCleanupTask.cleanupExpired();

        // ① 归档留痕了
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM deleted_data_archive WHERE resource_type = 'JOB' AND original_id = ?",
                Integer.class, jobId)).isEqualTo(1);
        // ② 业务行被物理删了（不是留成孤儿）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job WHERE id = ?", Integer.class, jobId))
                .isZero();
        // ③ 回收站记录也清了
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recycle_bin WHERE id = 980002", Integer.class))
                .isZero();
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
