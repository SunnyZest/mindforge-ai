package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 简历 PDF 导出接口。
 * <p>
 * 这里测的是「服务端渲染这件事本身成立」—— 二进制流、响应头、中文能不能被提取。
 * AI 解析被 mock 掉，结构化数据直接写库，所以用例不依赖模型也不花钱。
 *
 * @author crx
 * @since 2026-10-29
 */
@DisplayName("简历 PDF 导出")
class ResumePdfApiTest extends AbstractApiTest {

    /** 一份完整度较高的结构化简历，字段名与 AI 解析产物一致 */
    private static final String STRUCTURED_JSON = """
            {
              "name": "张伟",
              "phone": "13800000000",
              "email": "zhangwei@example.com",
              "location": "上海",
              "targetPosition": "Java 后端开发工程师",
              "yearsOfExperience": 3,
              "summary": "3 年 Java 后端经验，主导过订单中心分库分表改造。",
              "education": [
                {"school": "某某大学", "major": "软件工程", "degree": "本科",
                 "startYear": 2017, "endYear": 2021}
              ],
              "workExperience": [
                {"company": "某某科技有限公司", "position": "Java 后端开发",
                 "startDate": "2021-07", "endDate": "至今",
                 "description": "负责订单中心开发，QPS 从 800 提升到 4200。"}
              ],
              "projects": [
                {"name": "订单中心", "role": "核心开发",
                 "description": "完成分库分表改造，支持千万级订单。",
                 "techStack": ["Spring Boot", "MySQL", "ShardingSphere"]}
              ],
              "skills": [
                {"name": "Java", "type": "LANGUAGE", "proficiency": "ADVANCED", "years": 3},
                {"name": "Spring Boot", "type": "FRAMEWORK", "proficiency": "ADVANCED", "years": 3},
                {"name": "Redis", "type": "FRAMEWORK", "proficiency": "INTERMEDIATE", "years": 2}
              ],
              "certificates": ["软件设计师"],
              "selfEvaluation": "学习能力强，能扛事。"
            }
            """;

    private static final String SAMPLE_RESUME_TEXT = "张伟 男 28 岁 上海 Java 后端开发工程师";

    private String token;
    private long resumeId;

    @BeforeEach
    void prepareParsedResume() {
        token = loginAsUser();
        resumeId = uploadResume(token, "张伟-简历.txt", SAMPLE_RESUME_TEXT).assertSuccess().longId();
        markParsed(resumeId);
    }

    /** 模拟「已解析」状态：AI 被 mock，结构化数据只能直接写库 */
    private void markParsed(long id) {
        jdbc.update("UPDATE resume SET structured_json = ?, parse_status = 'SUCCESS' WHERE id = ?",
                STRUCTURED_JSON, id);
    }

    /** 导出接口返回二进制，基类的 JSON helper 用不了，这里单开一个 */
    private ResponseEntity<byte[]> export(long id, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange("/app/resumes/" + id + "/export",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    // ---------- 正常路径 ----------

    @Test
    @DisplayName("导出成功：返回 PDF 二进制流，不是 Result 信封")
    void exportReturnsPdfStream() {
        ResponseEntity<byte[]> response = export(resumeId, token);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);

        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        // PDF 魔数。这一条同时排掉「返回了 JSON 错误体但状态码是 200」的情况
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(body.length).isGreaterThan(2000);
    }

    @Test
    @DisplayName("响应头：附件下载 + 中文文件名走 RFC 5987 + 不允许缓存")
    void exportSetsDownloadHeaders() {
        ResponseEntity<byte[]> response = export(resumeId, token);
        HttpHeaders headers = response.getHeaders();

        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        // 中文文件名必须编码成 filename*=UTF-8''，否则浏览器拿到的是一串乱码
        assertThat(disposition).contains("filename*=UTF-8''");
        // 同时给一个纯 ASCII 的兜底名，老客户端不认 filename* 时用它
        assertThat(disposition).contains("filename=\"resume.pdf\"");

        // 解码后看真实文件名：没设目标职位时用姓名，且不能残留上传时的 .txt
        assertThat(decodedFileName(disposition)).isEqualTo("张伟-简历.pdf");

        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(headers.getContentLength()).isEqualTo(response.getBody().length);
    }

    @Test
    @DisplayName("文件名优先用目标职位")
    void fileNamePrefersTargetPosition() {
        jdbc.update("UPDATE resume SET target_position = ? WHERE id = ?", "Java 后端开发", resumeId);

        String disposition = export(resumeId, token).getHeaders()
                .getFirst(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(decodedFileName(disposition)).isEqualTo("简历-Java 后端开发.pdf");
    }

    /** 从 Content-Disposition 里取出 filename* 并解码成人类可读的文件名 */
    private String decodedFileName(String disposition) {
        String marker = "filename*=UTF-8''";
        int at = disposition.indexOf(marker);
        assertThat(at).as("响应头里没有 filename*：" + disposition).isGreaterThanOrEqualTo(0);
        return URLDecoder.decode(disposition.substring(at + marker.length()), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("中文能被 PDFBox 提取出来（证明字体内嵌正确，ATS 读得到）")
    void chineseTextIsExtractable() throws Exception {
        byte[] pdf = export(resumeId, token).getBody();
        assertThat(pdf).isNotNull();

        String text;
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            text = new PDFTextStripper().getText(doc);
        }

        // ⚠️ 这条是整套方案的核心守卫。
        //    换错字体（比如 CFF 轮廓的 OTF）时 PDF 照样能生成、人眼看也正常，
        //    但提取出来全是 # —— 只有这条断言能发现。
        assertThat(text)
                .as("PDF 里的中文提取失败，检查内嵌字体是不是 TrueType 轮廓（TTF）")
                .contains("张伟")
                .contains("某某科技有限公司")
                .contains("订单中心")
                .contains("软件设计师");

        // 技能名是英文，单独验一遍，避免「只有中文坏了」被中文断言掩盖
        assertThat(text).contains("Java").contains("Spring Boot");

        // ⚠️ 空格必须是普通空格 U+0020，不能是 NBSP U+00A0。
        //    思源黑体的 cmap 把 U+0020 和 U+00A0 指向同一个字形，PDFBox 子集化时
        //    反向映射取了后者 —— 结果是 PDF 里所有空格都变 NBSP，人眼看不出，
        //    但 ATS 拿 "Spring Boot" 去匹配 "Spring\u00a0Boot" 会失配。
        //    靠模板里把拉丁字体排在中文前面来规避，这条断言钉住它。
        assertThat(text)
                .as("PDF 里的空格变成了 NBSP（U+00A0），ATS 关键词匹配会失配")
                .doesNotContain("\u00a0");
    }

    @Test
    @DisplayName("渲染的是结构化数据，不是上传的原文")
    void rendersStructuredDataNotRawText() throws Exception {        byte[] pdf = export(resumeId, token).getBody();
        String text;
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            text = new PDFTextStripper().getText(doc);
        }

        // 原文里有「28 岁」，结构化数据里没有 —— 渲染原文的话这句会出现在 PDF 里
        assertThat(text).doesNotContain("28 岁");
    }

    @Test
    @DisplayName("结构化数据很稀疏时也能出 PDF（模型只认出姓名的情况）")
    void rendersSparseStructuredData() throws Exception {
        // 模型偶尔只认出姓名，其他字段全空。这种结果不该 500，也不该渲染出一堆空标题
        jdbc.update("UPDATE resume SET structured_json = ? WHERE id = ?", "{\"name\":\"李四\"}", resumeId);

        ResponseEntity<byte[]> response = export(resumeId, token);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        String text;
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(response.getBody()))) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("李四");
        // 没有任何经历的简历不该冒出「工作经历」这种空区块
        assertThat(text).doesNotContain("工作经历").doesNotContain("教育经历");
    }

    // ---------- 失败分支 ----------

    @Test
    @DisplayName("没解析过就导出：40001，且提示要说清原因")
    void exportUnparsedResumeIsRejected() {
        long pendingId = uploadResume(token, "未解析.txt", SAMPLE_RESUME_TEXT).assertSuccess().longId();

        ResponseEntity<byte[]> response = export(pendingId, token);
        assertThat(response.getStatusCode().value()).isEqualTo(400);

        JsonNode body = readJson(response);
        assertThat(body.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(body.get("message").asText()).contains("还没有解析");
    }

    @Test
    @DisplayName("导出别人的简历：404（不返回 403，避免泄漏「这个 id 存在」）")
    void exportOthersResumeIsNotFound() {
        createAccount("t_other");
        String otherToken = loginRaw("/app/auth/login", "t_other", TEST_PASSWORD)
                .assertSuccess().str("accessToken");

        ResponseEntity<byte[]> response = export(resumeId, otherToken);
        assertThat(response.getStatusCode().value()).isEqualTo(404);

        JsonNode body = readJson(response);
        assertThat(body.get("code").asInt()).isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("不带 token：401")
    void exportRequiresAuth() {
        assertThat(export(resumeId, null).getStatusCode().value()).isEqualTo(401);
    }

    /** 错误分支的响应体是 JSON 字节，转回 JsonNode 便于断言 code */
    private JsonNode readJson(ResponseEntity<byte[]> response) {
        try {
            byte[] body = response.getBody();
            assertThat(body).as("错误响应没有响应体").isNotNull();
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("错误响应体不是合法 JSON", e);
        }
    }
}
