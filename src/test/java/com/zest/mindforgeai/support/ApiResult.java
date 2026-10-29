package com.zest.mindforgeai.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一次 API 调用的结果：HTTP 状态码 + 响应信封。
 *
 * <p>断言方法都返回 {@code this}，可以链式写：
 * <pre>{@code
 * api.post("/app/jobs", token, body).assertSuccess().assertCamelCase();
 * }</pre>
 *
 * <p>失败信息里带上完整响应体 —— 只报「期望 0 实际 40001」等于没说，
 * 得能一眼看出 message 和服务端到底返回了什么。
 *
 * @author crx
 * @since 2026-10-10
 */
public record ApiResult(int httpStatus, JsonNode body) {

    /** 业务码。响应不是约定的信封时返回 -1，断言里能看出来是「格式不对」而不是「码不对」。 */
    public int code() {
        return body != null && body.hasNonNull("code") ? body.get("code").asInt() : -1;
    }

    public String message() {
        return body == null ? "" : body.path("message").asText("");
    }

    /** 响应体的 data 部分。注意：成功但无数据时是 null。 */
    public JsonNode data() {
        return body == null ? null : body.get("data");
    }

    public String traceId() {
        return body == null ? null : body.path("traceId").asText(null);
    }

    public boolean isSuccess() {
        return code() == 0;
    }

    /**
     * {@code data} 是否为空。
     * <p>
     * ★ <b>为什么必须用这个方法，不能写 {@code assertThat(res.data()).isNull()}</b>：
     * 业务失败时后端返回的是 {@code "data": null}，Jackson 把它解析成
     * {@link com.fasterxml.jackson.databind.node.NullNode} 这个<b>单例对象</b>，
     * 不是 Java 的 {@code null}。于是断言会报出这条让人怀疑人生的消息：
     * <pre>
     * expected: null
     *  but was: null
     * </pre>
     * 两个「null」根本不是一回事 —— 一个是 Java 引用，一个是 JSON 里的 null 节点。
     * <p>
     * 这里把两种空都收进来（key 不存在时 {@code data()} 返回 Java null，
     * 显式 {@code null} 时返回 NullNode），断言侧只需问一次「空不空」。
     */
    public boolean isDataNull() {
        JsonNode d = data();
        return d == null || d.isNull();
    }

    /** 取 data 下的一个字符串字段，缺失返回 null。 */
    public String str(String field) {
        JsonNode d = data();
        if (d == null || !d.hasNonNull(field)) {
            return null;
        }
        return d.get(field).asText();
    }

    /** 取 data 下的一个数字字段（用于 id —— 项目里 id 序列化成字符串，所以统一按字符串取）。 */
    public String id() {
        return str("id");
    }

    /**
     * 取 data.id 并转成 long。
     *
     * <p>用 {@code asLong()} 而不是 {@code Long.parseLong(str())} ——
     * 前者对「id 是字符串」和「id 是数字」两种序列化都成立，
     * 以后有人把 id 从 String 改回 Long，这里不用跟着改。
     */
    public long longId() {
        JsonNode d = data();
        if (d == null || !d.hasNonNull("id")) {
            throw new IllegalStateException("响应里没有 data.id：" + body);
        }
        return d.get("id").asLong();
    }

    // ---------- 断言 ----------

    public ApiResult assertSuccess() {
        assertThat(code())
                .as("期望业务成功（code=0），实际 http=%s code=%s message=%s body=%s",
                        httpStatus, code(), message(), body)
                .isZero();
        return this;
    }

    public ApiResult assertCode(int expected) {
        assertThat(code())
                .as("期望业务码 %s，实际 http=%s code=%s message=%s body=%s",
                        expected, httpStatus, code(), message(), body)
                .isEqualTo(expected);
        return this;
    }

    public ApiResult assertHttpStatus(int expected) {
        assertThat(httpStatus)
                .as("期望 HTTP %s，实际 %s body=%s", expected, httpStatus, body)
                .isEqualTo(expected);
        return this;
    }

    /**
     * 递归扫描 {@code data}，任何一层出现带下划线的键就失败。
     *
     * <p>为什么值得单独一个断言：Python 侧字段名写成 snake_case 时，
     * Java 的 Jackson 不会报错，只会让字段**静默变 null** —— HTTP 200、日志正常、
     * 数据是空的，排查方向全错。这个断言把那种错误挡在测试里。
     */
    public ApiResult assertCamelCase() {
        List<String> offenders = new ArrayList<>();
        collectSnakeCaseKeys(data(), "$.data", offenders);
        assertThat(offenders)
                .as("响应字段名必须是 camelCase，这些不是：%s（完整响应 %s）", offenders, body)
                .isEmpty();
        return this;
    }

    private static void collectSnakeCaseKeys(JsonNode node, String path, List<String> offenders) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().indexOf('_') >= 0) {
                    offenders.add(path + "." + e.getKey());
                }
                collectSnakeCaseKeys(e.getValue(), path + "." + e.getKey(), offenders);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectSnakeCaseKeys(node.get(i), path + "[" + i + "]", offenders);
            }
        }
    }
}
