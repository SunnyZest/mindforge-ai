package com.zest.mindforgeai.pojo.vo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 简历 PDF 模板的数据模型（给 Thymeleaf 用，不是对外的 API 响应）。
 * <p>
 * <b>为什么要有这一层，而不是把 {@code JsonNode} 直接丢进模板：</b>
 * 模板里做 {@code ${r.structured.get('workExperience')}} 这种表达式既难读，
 * 又要把「字段缺失 / 为 null / 类型不对」三种情况在模板里各写一遍。
 * 在这里一次性归一成「字符串 + 列表」，模板就只剩 {@code th:each} 和 {@code th:text}。
 * <p>
 * ⚠️ <b>字段顺序刻意按简历的阅读顺序排</b>，模板也按这个顺序渲染 ——
 * 顺序变了 ATS 提取出来的文本顺序也会变。
 *
 * @param docTitle       文档标题（PDF 元数据 / 浏览器标签），姓名优先
 * @param name           姓名
 * @param metaLine       联系方式行，已用 " | " 拼好
 * @param summary        个人总结
 * @param skills         技能名列表
 * @param workExperience 工作经历
 * @param projects       项目经历
 * @param education      教育经历
 * @param certificates   证书与奖项
 * @param selfEvaluation 自我评价
 * @author crx
 * @since 2026-10-29
 */
public record ResumePdfModel(
        String docTitle,
        String name,
        String metaLine,
        String summary,
        List<String> skills,
        List<Entry> workExperience,
        List<Entry> projects,
        List<Entry> education,
        List<String> certificates,
        String selfEvaluation) {

    /**
     * 一条经历。
     *
     * @param title    左侧主标题（公司 · 职位 / 项目名 · 角色 / 学校 · 专业 · 学历）
     * @param right    右侧附注（时间区间 / 技术栈），可为空串
     * @param descHtml 描述，<b>已做 HTML 转义并把换行转成 {@code <br/>}</b>，
     *                 模板里必须用 {@code th:utext} 输出
     */
    public record Entry(String title, String right, String descHtml) {

        /** 整条都没内容时模板要跳过，避免渲染出一个空行 */
        public boolean isEmpty() {
            return !StringUtils.hasText(title) && !StringUtils.hasText(right)
                    && !StringUtils.hasText(descHtml);
        }
    }

    /**
     * 从 AI 解析出的结构化简历（{@code resume.structured_json}）构造模板模型。
     * <p>
     * 这里对每个字段都做「取不到就是空」的兜底：解析结果是模型产出的，
     * 字段缺失、类型不符都发生过。宁可少渲染一块，也不能让导出整个 500。
     *
     * @param structured 结构化简历，调用方保证非 null
     * @param fallbackTitle 姓名缺失时用简历名称兜底
     */
    public static ResumePdfModel from(JsonNode structured, String fallbackTitle) {
        String name = text(structured, "name");
        String title = StringUtils.hasText(name) ? name
                : (StringUtils.hasText(fallbackTitle) ? fallbackTitle : "个人简历");

        return new ResumePdfModel(
                title,
                title,
                metaLine(structured),
                text(structured, "summary"),
                stringList(structured.path("skills"), "name"),
                entries(structured.path("workExperience"), ResumePdfModel::workEntry),
                entries(structured.path("projects"), ResumePdfModel::projectEntry),
                entries(structured.path("education"), ResumePdfModel::educationEntry),
                stringList(structured.path("certificates"), null),
                text(structured, "selfEvaluation"));
    }

    /* ---------- 各类经历的映射 ---------- */

    private static Entry workEntry(JsonNode n) {
        String company = text(n, "company");
        String position = text(n, "position");
        String range = range(text(n, "startDate"), text(n, "endDate"), "至今");
        return new Entry(join(" · ", company, position), range, richText(text(n, "description")));
    }

    private static Entry projectEntry(JsonNode n) {
        String projectName = text(n, "name");
        String role = text(n, "role");
        return new Entry(join(" · ", projectName, role),
                String.join(" / ", stringList(n.path("techStack"), null)),
                richText(text(n, "description")));
    }

    private static Entry educationEntry(JsonNode n) {
        String school = text(n, "school");
        String major = text(n, "major");
        String degree = text(n, "degree");
        String range = range(text(n, "startYear"), text(n, "endYear"), "");
        return new Entry(join(" · ", school, major, degree), range, "");
    }

    /* ---------- 小工具 ---------- */

    /** 联系方式行：只拼有值的项，全空时返回空串（模板据此隐藏这一行） */
    private static String metaLine(JsonNode s) {
        List<String> parts = new ArrayList<>();
        String target = text(s, "targetPosition");
        if (StringUtils.hasText(target)) {
            parts.add("意向岗位：" + target);
        }
        addIfPresent(parts, text(s, "phone"));
        addIfPresent(parts, text(s, "email"));
        addIfPresent(parts, text(s, "location"));

        JsonNode years = s.path("yearsOfExperience");
        if (years.isNumber() || years.isTextual() && StringUtils.hasText(years.asText())) {
            parts.add(years.asText() + " 年经验");
        }
        return String.join(" | ", parts);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value);
        }
    }

    /** 取文本字段。缺失、null、非标量一律当空串 —— 不做类型猜测 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.isContainerNode()) {
            return "";
        }
        return value.asText("").trim();
    }

    /**
     * 取数组里的某个字段。
     *
     * @param field 为 null 表示数组元素本身就是字符串（如 certificates）
     */
    private static List<String> stringList(JsonNode array, String field) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String value = field == null
                    ? (item.isValueNode() ? item.asText("").trim() : "")
                    : text(item, field);
            if (StringUtils.hasText(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static List<Entry> entries(JsonNode array, java.util.function.Function<JsonNode, Entry> mapper) {
        if (!array.isArray()) {
            return List.of();
        }
        List<Entry> result = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            Entry entry = mapper.apply(item);
            if (!entry.isEmpty()) {
                result.add(entry);
            }
        }
        return result;
    }

    private static String range(String start, String end, String endFallback) {
        String from = StringUtils.hasText(start) ? start : "";
        String to = StringUtils.hasText(end) ? end : endFallback;
        if (!StringUtils.hasText(from) && !StringUtils.hasText(to)) {
            return "";
        }
        if (!StringUtils.hasText(from)) {
            return to;
        }
        return StringUtils.hasText(to) ? from + " ~ " + to : from;
    }

    /** 用分隔符拼接，跳过空项 */
    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                present.add(part);
            }
        }
        return String.join(separator, present);
    }

    /**
     * 描述文本 → 可安全 {@code th:utext} 输出的 HTML 片段。
     * <p>
     * ⚠️ <b>顺序不能反</b>：先转义再插 {@code <br/>}。
     * 反过来的话用户写在描述里的 {@code <script>} 会被当成标签原样输出。
     */
    private static String richText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return HtmlUtils.htmlEscape(raw).replace("\r\n", "\n").replace("\n", "<br/>");
    }
}
