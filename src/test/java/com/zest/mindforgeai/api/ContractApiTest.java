package com.zest.mindforgeai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.support.AbstractApiTest;
import com.zest.mindforgeai.support.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对外契约：响应信封、分页结构、错误码、字段命名。
 *
 * <p>这些是前端已经依赖的形状 —— 改了前端会静默出错（类型是手写的，
 * 不会跟着后端变），所以值得单独一组用例守着。
 *
 * @author crx
 * @since 2026-10-10
 */
@DisplayName("对外契约")
class ContractApiTest extends AbstractApiTest {

    @Test
    @DisplayName("成功响应：{ code:0, message, data, traceId } 四个字段都在")
    void successEnvelopeHasAllFields() {
        ApiResult result = get("/app/knowledge-bases", loginAsUser()).assertSuccess();

        assertThat(result.body().has("code")).as("信封要有 code").isTrue();
        assertThat(result.body().has("message")).as("信封要有 message").isTrue();
        assertThat(result.body().has("data")).as("信封要有 data").isTrue();
        assertThat(result.traceId())
                .as("信封要有 traceId —— 排查线上问题时靠它把前端报错和日志对上，不能为 null")
                .isNotBlank();
        assertThat(result.data()).as("列表接口的 data 不该是 null").isNotNull();
    }

    @Test
    @DisplayName("失败响应：code 非 0，message 是给人看的话")
    void failureEnvelopeCarriesMessage() {
        ApiResult result = get("/app/resumes/99999999", loginAsUser());

        assertThat(result.code()).as("不存在的资源不能返回 code=0").isNotZero();
        assertThat(result.message()).as("失败要给 message").isNotBlank();
        assertThat(result.traceId()).as("失败响应也要带 traceId").isNotBlank();
    }

    @Test
    @DisplayName("分页结构：{ page, pageSize, total, records }")
    void pageShapeIsStable() {
        JsonNode data = get("/app/resumes?page=1&pageSize=10", loginAsUser())
                .assertSuccess().data();

        assertThat(data.has("page")).as("分页要有 page").isTrue();
        assertThat(data.has("pageSize")).as("分页要有 pageSize").isTrue();
        assertThat(data.has("total")).as("分页要有 total").isTrue();
        assertThat(data.has("records")).as("分页要有 records").isTrue();
        assertThat(data.get("records").isArray()).as("records 必须是数组").isTrue();
    }

    @Test
    @DisplayName("参数校验失败：HTTP 400 + 40001")
    void validationFailureIsBadRequest() {
        ApiResult result = post("/app/jobs", loginAsUser(), Map.of("title", ""));

        result.assertHttpStatus(400).assertCode(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("资源不存在：HTTP 404 + 404xx")
    void missingResourceIsNotFound() {
        ApiResult result = get("/app/resumes/99999999", loginAsUser());

        result.assertHttpStatus(404);
        assertThat(result.code())
                .as("404 类错误码应该在 404xx 区间，实际 %s", result.code())
                .isBetween(40400, 40499);
    }

    @Test
    @DisplayName("★ 字段名必须是 camelCase（snake_case 会让 Jackson 静默变 null）")
    void responsesUseCamelCaseEverywhere() {
        String token = loginAsUser();

        // 覆盖几个字段结构差别较大的接口：分页、详情、列表
        get("/app/resumes?page=1&pageSize=5", token).assertSuccess().assertCamelCase();
        get("/app/knowledge-bases?page=1&pageSize=5", token).assertSuccess().assertCamelCase();
        get("/app/jobs?page=1&pageSize=5", token).assertSuccess().assertCamelCase();
        get("/app/interviews?page=1&pageSize=5", token).assertSuccess().assertCamelCase();
        get("/app/auth/me", token).assertSuccess().assertCamelCase();
    }

    @Test
    @DisplayName("id 序列化成字符串（前端按 string 处理，避免 JS 大整数精度丢失）")
    void idsAreSerializedAsStrings() {
        // 这条是刻意的契约：雪花 ID 改造后 id 会超出 JS Number 的安全整数范围，
        // 现在就把 id 定成字符串，前端不用等改造完再改一遍。
        ApiResult created = post("/app/knowledge-bases", loginAsUser(),
                Map.of("name", "契约测试知识库")).assertSuccess();

        JsonNode id = created.data().get("id");
        assertThat(id).as("创建接口要返回 id").isNotNull();
        assertThat(id.isTextual())
                .as("id 应该是 JSON 字符串，实际是 %s（%s）", id.getNodeType(), id)
                .isTrue();
    }

    @Test
    @DisplayName("未知路径：404，不是 500")
    void unknownPathIsNotFound() {
        ApiResult result = get("/app/definitely-not-a-real-endpoint", loginAsUser());

        assertThat(result.httpStatus())
                .as("未知路径应该是 404，实际 %s body=%s", result.httpStatus(), result.body())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("支持的方法用错：405，不是 500")
    void wrongMethodIsNotServerError() {
        // /app/auth/login 只接受 POST
        ApiResult result = get("/app/auth/login", null);

        assertThat(result.httpStatus())
                .as("方法不支持应该是 405，实际 %s body=%s", result.httpStatus(), result.body())
                .isIn(404, 405);
    }

    @Test
    @DisplayName("请求体不是合法 JSON：400，不是 500")
    void malformedJsonIsBadRequest() {
        // 40002 请求格式错误
        ApiResult result = post("/app/auth/login", null, "这不是 JSON");
        assertThat(result.httpStatus())
                .as("非法 JSON 应该是 400，实际 %s body=%s", result.httpStatus(), result.body())
                .isEqualTo(400);
    }
}
