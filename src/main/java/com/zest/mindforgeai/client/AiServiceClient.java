package com.zest.mindforgeai.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.context.TraceIdHolder;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.AiServiceProperties;
import com.zest.mindforgeai.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 调 Python AI 服务（Java → Python 方向）。
 * <p>
 * 走 {@code /internal/ai/v1/**}，鉴权用 {@code X-Service-Token} 头 ——
 * 和 Python 调 Java 用的是同一个 token 值。
 * <p>
 * <b>关于超时</b>：对话接口要等大模型逐 token 生成完整回答，
 * 几十秒是正常的，所以读超时按最慢的接口配（默认 120 秒）。
 * 配短了会大面积超时，而且错误信息看起来像「AI 服务挂了」，很容易误判。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Component
public class AiServiceClient {

    private static final String HEADER_SERVICE_TOKEN = "X-Service-Token";

    /**
     * 链路 id 的请求头名。
     *
     * <p>带过去是为了让 Python 上报 AI 使用量时能把它带回来 ——
     * 这样从一条 traceId 就能反查出这次调用消耗了多少 token
     * （{@code ai_usage_record.request_id}，见 {@code POST /internal/ai-usage}）。
     */
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    /**
     * 触发用户 id 的请求头名。
     *
     * <p>带过去是为了让 Python 上报 AI 使用量时知道「是谁触发的」——
     * Python 那边不需要为记账去改一堆 service 函数的签名（见 {@code ai_usage} 的说明）。
     */
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String PATH_CHAT = "/internal/ai/v1/rag/chat";
    private static final String PATH_CHAT_STREAM = "/internal/ai/v1/rag/chat/stream";
    private static final String PATH_SEARCH = "/internal/ai/v1/rag/search";
    private static final String PATH_RESUME_PARSE = "/internal/ai/v1/resumes/parse";
    private static final String PATH_RESUME_ANALYZE = "/internal/ai/v1/resumes/analyze";
    private static final String PATH_RESUME_MATCH = "/internal/ai/v1/resumes/match";
    private static final String PATH_SALARY_ESTIMATE = "/internal/ai/v1/salary/estimate";
    private static final String PATH_JOB_PARSE = "/internal/ai/v1/jobs/parse";
    private static final String PATH_INTERVIEW_GENERATE = "/internal/ai/v1/interviews/generate";
    private static final String PATH_INTERVIEW_ANALYZE = "/internal/ai/v1/interviews/analyze";
    private static final String PATH_INTERVIEW_REPORT = "/internal/ai/v1/interviews/report";
    private static final String PATH_URL_EXTRACT = "/internal/ai/v1/tools/url-extract";
    private static final String PATH_INVITATION_EXTRACT = "/internal/ai/v1/calendar/extract-invitation";
    private static final String PATH_STATUS = "/internal/ai/v1/status";
    private static final String PATH_CONFIG_EFFECTIVE = "/internal/ai/v1/config/effective";

    /** 状态探测的超时 —— 必须短，理由见 probeClient 字段 */
    private static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PROBE_READ_TIMEOUT = Duration.ofSeconds(3);

    /** 邀约抽取的超时 —— 必须短于前端的 120 秒，理由见 invitationClient 字段 */
    private static final Duration INVITATION_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration INVITATION_READ_TIMEOUT = Duration.ofSeconds(115);

    /** 配置快照的超时 —— 读的是 Python 内存里的 dict，5 秒都算宽松，理由见 configClient 字段 */
    private static final Duration CONFIG_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CONFIG_READ_TIMEOUT = Duration.ofSeconds(5);

    /** SSE 里数据行的前缀（Python 侧格式是 {@code data: {json}}） */
    private static final String SSE_DATA_PREFIX = "data:";

    private final AiServiceProperties properties;
    private final RestClient restClient;

    /**
     * 状态探测专用客户端。
     * <p>
     * ★ <b>为什么不能复用 {@link #restClient}</b>：它的读超时按最慢的接口定成了 660 秒
     * （对话、简历/JD 解析都要等模型生成）。状态徽章是首页上的东西 ——
     * Python 一旦「端口通但卡住」，用同一个客户端会让首页白等 11 分钟。
     * 所以这里单独给一个 2s 连接 / 3s 读取的短超时客户端。
     */
    private final RestClient probeClient;

    /**
     * 邀约抽取专用客户端。
     * <p>
     * ★ <b>为什么也不能复用 {@link #restClient}</b>：它的读超时是 660 秒，
     * 而邀约抽取是<b>用户盯着进度条等</b>的同步接口，前端自己 120 秒就断了。
     * 用 660 秒的话，Python 一旦卡住，用户会在 120 秒时收到一个网络错误
     * （看到的是「请求超时」，而不是后端给的「AI 服务超时，请稍后重试」），
     * 同时 Java 线程还要再空等 9 分钟。
     * <p>
     * 115 秒这个数的取法：<b>略小于前端的 120 秒</b>。这样正常慢请求（预期 10~20 秒、
     * 最坏 120 秒）在 115 秒内都还能拿到结果；真超时了也由后端先返回业务错误，
     * 前端拿到的是能展示的文案，而不是自己断连。
     */
    private final RestClient invitationClient;

    /**
     * 配置快照专用客户端。
     * <p>
     * ★ <b>为什么不复用 {@link #restClient}</b>：那个读超时是 660 秒（等模型生成用的）。
     * 而「当前生效配置」读的是 Python 内存里的一个 dict，正常耗时是毫秒级。
     * 用 660 秒的话，Python 一旦卡住，管理端那个页面会一直转圈，
     * 管理员会以为「接口没做」而不是「Python 挂了」。
     * <p>
     * 5 秒的取法：内存快照本身没有 IO，5 秒足够覆盖 GC / 线程调度抖动；
     * 真超过 5 秒，那一定是 Python 出了问题，早点报错比干等有用。
     */
    private final RestClient configClient;

    private final ObjectMapper objectMapper;

    public AiServiceClient(AiServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HEADER_SERVICE_TOKEN, properties.getServiceToken())
                .build();

        // 状态探测用的短超时客户端（理由见 probeClient 字段的注释）
        SimpleClientHttpRequestFactory probeFactory = new SimpleClientHttpRequestFactory();
        probeFactory.setConnectTimeout((int) PROBE_CONNECT_TIMEOUT.toMillis());
        probeFactory.setReadTimeout((int) PROBE_READ_TIMEOUT.toMillis());

        this.probeClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(probeFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HEADER_SERVICE_TOKEN, properties.getServiceToken())
                .build();

        // 邀约抽取用的中短超时客户端（理由见 invitationClient 字段的注释）
        SimpleClientHttpRequestFactory invitationFactory = new SimpleClientHttpRequestFactory();
        invitationFactory.setConnectTimeout((int) INVITATION_CONNECT_TIMEOUT.toMillis());
        invitationFactory.setReadTimeout((int) INVITATION_READ_TIMEOUT.toMillis());

        this.invitationClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(invitationFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HEADER_SERVICE_TOKEN, properties.getServiceToken())
                .build();

        // 配置快照用的短超时客户端（理由见 configClient 字段的注释）
        SimpleClientHttpRequestFactory configFactory = new SimpleClientHttpRequestFactory();
        configFactory.setConnectTimeout((int) CONFIG_CONNECT_TIMEOUT.toMillis());
        configFactory.setReadTimeout((int) CONFIG_READ_TIMEOUT.toMillis());

        this.configClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(configFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HEADER_SERVICE_TOKEN, properties.getServiceToken())
                .build();
    }

    /**
     * 基于知识库问答。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT
     */
    public ChatData chat(ChatRequest request) {
        AiEnvelope<ChatData> envelope = post(PATH_CHAT, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 只检索，不生成回答。
     */
    public SearchData search(SearchRequest request) {
        AiEnvelope<SearchData> envelope = post(PATH_SEARCH, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 探测 AI 服务与模型配置状态（首页状态徽章用）。
     * <p>
     * 走 {@link #probeClient}（短超时），失败抛异常，由调用方兜底转「离线」——
     * 这个接口存在的意义就是<b>不让首页被慢的 AI 服务拖垮</b>。
     *
     * @throws BizException AI_SERVICE_TIMEOUT / AI_SERVICE_ERROR
     */
    public StatusData status() {
        try {
            AiEnvelope<StatusData> envelope = probeClient.get()
                    .uri(PATH_STATUS)
                    .header(HEADER_TRACE_ID, currentTraceId())
                    .header(HEADER_USER_ID, currentUserId())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (envelope == null || envelope.code() == null || envelope.code() != 0 || envelope.data() == null) {
                throw new BizException(ErrorCode.AI_SERVICE_ERROR, "AI 服务状态响应异常");
            }
            return envelope.data();
        } catch (ResourceAccessException e) {
            throw new BizException(ErrorCode.AI_SERVICE_TIMEOUT, "AI 服务不可用");
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.AI_SERVICE_ERROR, "AI 服务状态读取失败");
        }
    }

    /**
     * 流式对话：边生成边把事件回调给调用方。
     * <p>
     * Python 侧返回的是 SSE（{@code text/event-stream}），事件序列：
     * <pre>
     *   sources → delta × N → done
     *                    ↘ error（出问题时【代替】done）
     * </pre>
     * <b>为什么先推 sources</b>：检索在调模型之前就完成了，先推给前端，
     * 用户能立刻看到「参考了哪几段」，不用等生成完（实测首字节 0.45 秒，
     * 而完整回答要 8.7 秒）。
     * <p>
     * ⚠️ <b>这是同步阻塞方法</b> —— 它会一直读到流结束（几十秒）才返回。
     * 调用方如果是在 HTTP 请求线程上调的，必须自己丢到异步线程里。
     * <p>
     * ⚠️ <b>只解析 {@code data:} 行，不看 {@code event:} 行</b> ——
     * Python 侧刻意把 {@code type} 也放进了 data 里，就是为了让这里省掉一个状态机
     * （不用记住「上一条 event 是什么」）。见 Python 侧 {@code _sse()} 的说明。
     *
     * @param handler 事件回调。⚠️ 它在【读取流的那个线程】上被调用，别在里面做重活
     * @throws BizException 连不上 / 读超时（AI_SERVICE_TIMEOUT）、
     *                      非 2xx（按 Python 的错误码翻译）、流中断（AI_SERVICE_ERROR）
     */
    public void chatStream(ChatRequest request, ChatStreamHandler handler) {
        try {
            restClient.post()
                    .uri(PATH_CHAT_STREAM)
                    .header(HEADER_TRACE_ID, currentTraceId())
                    .header(HEADER_USER_ID, currentUserId())
                    .body(request)
                    .exchange((req, res) -> {
                        readSseStream(res, handler);
                        return null;
                    });

        } catch (BizException e) {
            throw e;

        } catch (ResourceAccessException e) {
            log.error("流式对话连接失败 path={}", PATH_CHAT_STREAM, e);
            throw new BizException(ErrorCode.AI_SERVICE_TIMEOUT, "AI 服务暂时不可用，请稍后重试");

        } catch (RestClientException e) {
            // 流读到一半断了 / 响应读不出来。
            // ⚠️ 走到这里时【前端可能已经看到部分回答】了，错误信息要说清这一点，
            //    否则用户会把半截回答当成完整答案（同 Python 侧 LlmCallError 的处理）。
            log.error("流式对话中断 path={}", PATH_CHAT_STREAM, e);
            throw new BizException(ErrorCode.AI_SERVICE_ERROR, "AI 回答中断，已输出的内容可能不完整");

        } catch (Exception e) {
            log.error("流式对话出现未知错误 path={}", PATH_CHAT_STREAM, e);
            throw new BizException(ErrorCode.AI_SERVICE_ERROR, "调用 AI 服务失败");
        }
    }

    /**
     * 读 SSE 流并逐条回调。
     * <p>
     * 只认 {@code data:} 开头的行：{@code event:} 行和空行都跳过
     * （理由见 {@link #chatStream}）。
     */
    private void readSseStream(ClientHttpResponse res, ChatStreamHandler handler) throws IOException {
        HttpStatusCode status = res.getStatusCode();
        if (!status.is2xxSuccessful()) {
            // ⚠️ 这里能正常读到状态码，是因为【流还没开始推】——
            //    Python 侧一旦开始推事件，响应头就发出去了，状态码只能是 200，
            //    所有运行时错误改走 error 事件。见 internal.py 里的三条约束。
            String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            log.error("AI 服务返回异常状态 path={} status={} body={}",
                    PATH_CHAT_STREAM, status, body);
            throw translateErrorStatus(status, body);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SSE_DATA_PREFIX)) {
                    continue;
                }
                String payload = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (!payload.isEmpty()) {
                    dispatchSseEvent(payload, handler);
                }
            }
        }
    }

    /** 按 data 里的 {@code type} 分发。解析不了的事件跳过，不让整条流挂掉 */
    private void dispatchSseEvent(String payload, ChatStreamHandler handler) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("流式对话的事件不是合法 JSON，已跳过：{}", payload);
            return;
        }

        String type = node.path("type").asText("");
        switch (type) {
            case "sources" -> handler.onSources(
                    objectMapper.convertValue(node.path("sources"),
                            new TypeReference<List<Source>>() {
                            }),
                    node.path("hasContext").asBoolean(false),
                    node.path("contextTruncated").asBoolean(false));
            case "delta" -> handler.onDelta(node.path("text").asText(""));
            case "done" -> handler.onDone(
                    node.path("answer").asText(null),
                    node.path("hasAnswer").asBoolean(false));
            case "error" -> handler.onError(
                    node.path("code").asInt(ErrorCode.AI_SERVICE_ERROR.getCode()),
                    node.path("message").asText("AI 服务出错"));
            default -> log.debug("流式对话出现未知事件类型，已跳过：{}", type);
        }
    }

    /**
     * 解析简历：Python 侧下载文件 → 提取文本 → 模型结构化成 JSON。
     * <p>
     * 文本提取（PDF / DOCX 解析）只有 Python 侧会做，Java 没有解析器 ——
     * 所以提取出来的原文（{@code rawText}）会回传，由 Java 存进 {@code resume.raw_content}，
     * 供后续匹配 / 优化复用，避免每次重新下载解析。
     * <p>
     * ⚠️ <b>同步接口，一次调用可能几十秒</b>（下载 + 提取 + 模型生成）。
     * 读超时按最慢的接口配（默认 120 秒），配短了会大面积超时。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT，
     *                      或由 Python 侧错误码翻译而来（如 42201 文件读不了）
     */
    public ResumeParseData parseResume(ResumeParseRequest request) {
        AiEnvelope<ResumeParseData> envelope = post(PATH_RESUME_PARSE, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 解析职位 JD：技能 / 经验 / 学历 / 职责。
     * <p>
     * 和 {@link #parseResume} 不同，JD 的输入就是 {@code job.raw_content}，
     * 不需要下载文件，所以参数只有文本。
     */
    public JobParseData parseJob(JobParseRequest request) {
        AiEnvelope<JobParseData> envelope = post(PATH_JOB_PARSE, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 简历优化建议：针对目标岗位指出该改哪几句。
     * <p>
     * ⚠️ <b>只回建议，不改简历、也不落库</b>（文档 §二十一 明确要求
     * 「AI 优化只返回建议，不直接覆盖原始简历」）。所以这个接口没有任何写操作，
     * 调用方拿到结果直接返回给前端即可。
     * <p>
     * ★ 返回值里 {@code needsFact = true} 的条目表示「这里缺事实，要用户自己补」，
     * 此时 {@code rewritten} 一定是 null、由 {@code missingFact} 说清要补什么。
     * <b>前端必须把这两类分开展示</b> —— 混在一起用户会以为 AI 已经替他把经历写好了，
     * 而编造的经历在面试里被追问两句就露馅。
     * <p>
     * ⚠️ 慢接口（模型要写 8 条建议，实测几分钟），读超时按最慢的接口配。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT
     */
    public ResumeAnalyzeData analyzeResume(ResumeAnalyzeRequest request) {
        AiEnvelope<ResumeAnalyzeData> envelope = post(PATH_RESUME_ANALYZE, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 简历与职位匹配分析。
     * <p>
     * ★ 结果的核心是 {@code items}：<b>逐条要求落到五类结论之一</b>
     * （已匹配 / 表达缺口 / 证据不足 / 真实缺口 / 待确认），
     * 每一类都直接指向一个动作。{@code overallScore} 只是展示辅助，
     * 不参与任何准入判断 —— 「73%」说了等于没说。
     * <p>
     * ★ 和 {@link #analyzeResume} 的区别：那个是「这份简历自己怎么写得更好」
     * （不需要参照物），这个是「对【这个岗位】够不够」（必须有 JD 才成立）。
     * <p>
     * ⚠️ 慢接口（实测 100~200 秒），读超时按最慢的接口配。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT
     */
    public ResumeMatchData matchResume(ResumeMatchRequest request) {
        AiEnvelope<ResumeMatchData> envelope = post(PATH_RESUME_MATCH, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 薪酬估算：按城市 / 岗位 / 年限推算 P50 / P75 / P90 参考区间。
     * <p>
     * ★ <b>这是估算，不是行情</b>：我们没有真实薪酬样本，模型是按其训练数据里的
     * 岗位画像在推。所以：
     * <ul>
     *   <li>调用方必须把 {@code caveat}（免责声明）落库并返回给前端；</li>
     *   <li>{@code confidence} 只是模型的看法，<b>不能直接采信</b> ——
     *       服务端还要按城市 / 岗位 / 年限的常见度再钳一次；</li>
     *   <li>不要给用户看「样本量」「数据来源」之类我们根本没有的东西。</li>
     * </ul>
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT
     */
    public SalaryEstimateData estimateSalary(SalaryEstimateRequest request) {
        AiEnvelope<SalaryEstimateData> envelope = post(PATH_SALARY_ESTIMATE, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 面试出题：根据简历和 JD 一次性出完全部题目。
     * <p>
     * ⚠️ 每题都带 {@code expectedPoints}（参考答案要点），调用方必须把它存进
     * {@code interview_question.expected_points} —— 后面评分时再带回来。
     * 没有这个参照物，模型对同一份回答的打分会飘（这次 70 下次 85）。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT
     */
    public InterviewGenerateData generateQuestions(InterviewGenerateRequest request) {
        AiEnvelope<InterviewGenerateData> envelope = post(PATH_INTERVIEW_GENERATE, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 给单道题的回答打分并分析。
     * <p>
     * ⚠️ {@code answer} 允许传空串 —— 跳过一道题是正常行为，
     * Python 侧会给低分而不是报错。报错的话用户跳过一题整场面试就卡住了。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT
     */
    public InterviewAnalyzeData analyzeAnswer(InterviewAnalyzeRequest request) {
        AiEnvelope<InterviewAnalyzeData> envelope = post(PATH_INTERVIEW_ANALYZE, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 生成整场面试的复盘报告。
     * <p>
     * ⚠️ 必须把逐题得分和逐题分析一起带上（{@link InterviewReportRequest#items()}）——
     * 报告要求「四个分数和逐题得分对得上」，不给逐题分模型只能瞎给，
     * 用户会发现「每题都 80 综合却给 60」而不再信任报告。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT
     */
    public InterviewReportData buildReport(InterviewReportRequest request) {
        AiEnvelope<InterviewReportData> envelope = post(PATH_INTERVIEW_REPORT, request,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 网页正文提取（基于 Python 原生 trafilatura 工具）。
     */
    public UrlExtractData extractUrl(String url) {
        AiEnvelope<UrlExtractData> envelope = post(PATH_URL_EXTRACT, new UrlExtractRequest(url),
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 从面试邀约原文里抽日程（HR 发来的邮件 / 微信邀面词 / 截图 / PDF）。
     * <p>
     * ★ <b>Python 只负责「抽」，不负责「判」</b>：每个字段都带 {@code evidence}
     * （支撑该值的原文片段），证据校验、枚举钳制、职位匹配全在 Java 这边做
     * （见 {@code InvitationExtractServiceImpl}）。这样模型不听话也不会把脏数据
     * 塞到用户面前 —— 校验逻辑在能被单测覆盖的地方。
     * <p>
     * ⚠️ <b>必须回传 {@code sourceText}</b>：{@code source=FILE} 时 Java 手上没有原文
     * （PDF / DOCX / 图片都只有 Python 能解析），没有原文就没法做证据校验。
     * 所以 Python 侧要把提取出来的正文一起回传，<b>但它不会返回给前端</b>。
     * <p>
     * ⚠️ 走 {@link #invitationClient}（115 秒），<b>不是</b> 660 秒的主客户端。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT，
     *                      或由 Python 侧错误码翻译而来（如 42201 文件读不出文字）
     */
    public InvitationExtractData extractInvitation(InvitationExtractRequest request) {
        AiEnvelope<InvitationExtractData> envelope = post(invitationClient, PATH_INVITATION_EXTRACT,
                request, new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    /**
     * 当前<b>真正生效</b>的 AI 配置快照（管理端「当前生效」展示用）。
     * <p>
     * ★ <b>这是转发，不是计算</b>：生效规则是 Python 侧的
     * {@code ai_config.get()}（{@code 缓存优先，没有就回退 .env}），
     * 而且 .env 里的模型名只有 Python 看得见。Java 这边<b>不要</b>拿
     * {@code sys_ai_model} 自己算一遍 —— 两套逻辑必然漂移，
     * 而这种漂移最难查（两边都觉得自己对）。
     * <p>
     * ★ 解决的两个「管理端看不出来」的坑：
     * <ol>
     *   <li>某个用途有多条配置但都没勾默认时，实际生效的是 <b>id 最大</b>的那条；</li>
     *   <li>某个用途一条都没配时，实际走 <b>.env</b>，管理端完全看不到。</li>
     * </ol>
     * 这两种情况下列表都答不出「到底在用什么」，只能去翻
     * {@code ai_usage_record.model_name}。
     * <p>
     * ⚠️ 响应里<b>不含任何 api_key</b>，只有 {@code hasKey} 布尔。
     * <p>
     * ⚠️ 走 {@link #configClient}（5 秒），不是 660 秒的主客户端。
     *
     * @throws BizException AI_SERVICE_ERROR / AI_SERVICE_TIMEOUT（Python 没起来时）
     */
    public EffectiveConfigData effectiveConfig() {
        AiEnvelope<EffectiveConfigData> envelope = get(configClient, PATH_CONFIG_EFFECTIVE,
                new ParameterizedTypeReference<>() {
                });
        return envelope.data();
    }

    // ---------- 内部 ----------

    /**
     * ⚠️ 这里必须用 {@link ParameterizedTypeReference} 而不是 {@code AiEnvelope.class}。
     * <p>
     * 传 {@code Class} 对象会把泛型信息丢掉 —— Jackson 只能把 {@code data} 反序列化成
     * {@code LinkedHashMap}，取出来强转成 {@code ChatData} 就抛 ClassCastException。
     * 实测踩过，错误信息是「系统内部异常」，很容易往错的方向查。
     * <p>
     * {@code ParameterizedTypeReference} 是匿名子类，能在运行期保留
     * {@code AiEnvelope<ChatData>} 这个完整类型，Jackson 就能正确反序列化。
     */
    private <T> AiEnvelope<T> post(String path, Object body,
                                   ParameterizedTypeReference<AiEnvelope<T>> typeRef) {
        return post(restClient, path, body, typeRef);
    }

    /**
     * 同 {@link #post(String, Object, ParameterizedTypeReference)}，但指定用哪个客户端。
     * <p>
     * 只有超时预算不同的接口需要（状态探测 / 邀约抽取 / 配置快照各有一个专用客户端）。
     * 其余接口一律走主客户端，别为了「顺手」把慢接口也换成短超时的那个。
     */
    private <T> AiEnvelope<T> post(RestClient client, String path, Object body,
                                   ParameterizedTypeReference<AiEnvelope<T>> typeRef) {
        return call(path, () -> client.post()
                .uri(path)
                // ⚠️ traceId 必须用【请求级】header 带过去，不能放进 defaultHeader ——
                //    defaultHeader 在构造时求值一次，而 traceId 是每个请求一个（ThreadLocal），
                //    那样只会带上一串 null。
                .header(HEADER_TRACE_ID, currentTraceId())
                .header(HEADER_USER_ID, currentUserId())
                .body(body)
                .retrieve()
                .body(typeRef));
    }

    /**
     * GET 版本。目前只有「配置快照」这类纯读接口用得上。
     * <p>
     * 和 {@link #post} 共用 {@link #call} 的异常翻译 —— 两个分支各写一遍的话，
     * 迟早会出现「同一个 Python 错误码，POST 翻成 42201、GET 翻成 50201」这种事。
     */
    private <T> AiEnvelope<T> get(RestClient client, String path,
                                  ParameterizedTypeReference<AiEnvelope<T>> typeRef) {
        return call(path, () -> client.get()
                .uri(path)
                .header(HEADER_TRACE_ID, currentTraceId())
                .header(HEADER_USER_ID, currentUserId())
                .retrieve()
                .body(typeRef));
    }

    /**
     * 发请求 + 统一校验信封 + 统一翻译异常。
     * <p>
     * 抽成「传一个 Supplier 进来」而不是把两种 HTTP 方法各写一遍 try/catch：
     * 这里的 catch 分支有 5 个，每个都对应一次真实踩坑（见各分支注释），
     * 复制两份必然有一份先腐烂。
     */
    private <T> AiEnvelope<T> call(String path, Supplier<AiEnvelope<T>> request) {
        try {
            AiEnvelope<T> envelope = request.get();

            if (envelope == null) {
                throw new BizException(ErrorCode.AI_SERVICE_ERROR, "AI 服务返回了空响应");
            }
            // Python 侧业务错误（参数校验等）也用 code 表达，这里统一转成 Java 的异常
            if (envelope.code() == null || envelope.code() != 0) {
                throw new BizException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 服务返回错误：" + envelope.message());
            }
            if (envelope.data() == null) {
                throw new BizException(ErrorCode.AI_SERVICE_ERROR, "AI 服务返回的数据为空");
            }
            return envelope;

        } catch (BizException e) {
            throw e;

        } catch (ResourceAccessException e) {
            // 连不上 / 读超时。这两种在 RestClient 里都是 ResourceAccessException，
            // 靠 message 区分太脆，统一按「AI 服务超时或不可用」报，并把原因带上
            log.error("调用 AI 服务失败 path={}", path, e);
            throw new BizException(ErrorCode.AI_SERVICE_TIMEOUT,
                    "AI 服务暂时不可用，请稍后重试");

        } catch (RestClientResponseException e) {
            // 注意变量名：不能叫 body —— 那是本方法参数的名字，Java 不允许内层作用域遮蔽参数
            String errorBody = e.getResponseBodyAsString();
            log.error("AI 服务返回异常状态 path={} status={} body={}",
                    path, e.getStatusCode(), errorBody);
            throw translateErrorStatus(e.getStatusCode(), errorBody);

        } catch (RestClientException e) {
            // 走到这里说明【状态码是 2xx，但响应体读不出来】。
            //
            // 最常见的原因：本端读超时【先于】服务端触发 —— 连接被掐断，响应只剩半截，
            // 连 Content-Type 都读不到，Spring 就按 application/octet-stream 去找消息转换器，
            // 报「Error while extracting response ... content type [application/octet-stream]」。
            // 这条信息完全指不出真因（看着像格式问题），实测被它带偏过一次，所以单独识别。
            //
            // 根治办法是让本端超时长于服务端（见 application.yml 的 read-timeout 说明）。
            log.error("AI 服务响应读取失败（多半是本端读超时先于服务端触发）path={}", path, e);
            throw new BizException(ErrorCode.AI_SERVICE_TIMEOUT,
                    "AI 服务响应超时或连接中断，请稍后重试");

        } catch (Exception e) {
            log.error("调用 AI 服务出现未知错误 path={}", path, e);
            throw new BizException(ErrorCode.AI_SERVICE_ERROR, "调用 AI 服务失败");
        }
    }

    /**
     * 把响应体解析成信封，取里面的错误码和提示。
     * <p>
     * 解析不了就返回 null —— 走到这里说明状态码已经不对了，
     * 响应体完全可能是网关 / 反代的 HTML 错误页，不是我们约定的 JSON。
     * 那种情况下抛异常只会把原始错误盖掉。
     */
    /**
     * 当前请求的 traceId；拿不到就给空串。
     *
     * <p>⚠️ 注意是「当前请求」—— 异步线程里 TraceIdHolder 是空的
     * （ThreadLocal），所以调用方必须在请求线程上先捕获再带进去，
     * 这一点和 {@code SecurityContext} 一样，见 ConversationController.sendMessageStream。
     */
    private static String currentTraceId() {
        String traceId = TraceIdHolder.get();
        return traceId == null ? "" : traceId;
    }

    /**
     * 当前请求的用户 id；拿不到（未登录、或异步线程里上下文为空）就给空串。
     *
     * <p>⚠️ 不能直接用 {@code SecurityUtils.getUserId()} —— 它在未登录时抛 40101，
     * 而「带上是谁触发的」只是记账用的附加信息，不该让一次已经通过鉴权的 AI 调用挂掉。
     */
    private static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)
                || loginUser.getUserId() == null) {
            return "";
        }
        return String.valueOf(loginUser.getUserId());
    }

    private AiEnvelope<Object> parseEnvelope(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.debug("AI 服务的异常响应不是约定的 JSON 信封，忽略：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把「非 2xx + 响应体」翻译成业务异常。
     * <p>
     * ★ Python 侧的错误码和本项目的 {@link ErrorCode} 是<b>同一套数值</b>
     * （见 Python 侧 {@code app/main.py} 的各 handler），所以先试着翻回枚举。
     * 不这么做的话，「这个 PDF 是扫描件、读不出文字」(42201) 会退化成一个
     * 笼统的 50201「AI 服务异常」—— 用户看到「服务异常」会以为重试就好，
     * 而真正该说的是「换个能复制文字的 PDF」。排查方向也被带偏。
     * <p>
     * 两个调用点共用（{@link #post} 和 {@link #chatStream}），避免两边翻译得不一样。
     */
    private BizException translateErrorStatus(HttpStatusCode status, String body) {
        AiEnvelope<Object> envelope = parseEnvelope(body);
        if (envelope != null && envelope.code() != null && envelope.code() != 0) {
            return new BizException(
                    ErrorCode.fromCode(envelope.code()).orElse(ErrorCode.AI_SERVICE_ERROR),
                    envelope.message());
        }
        return new BizException(ErrorCode.AI_SERVICE_ERROR,
                "AI 服务返回异常状态：" + status);
    }

    /**
     * 流式对话的事件回调。
     * <p>
     * 四个方法对应 Python 侧的四种事件，语义见 {@link #chatStream}。
     * 抽成接口而不是返回一个 List —— 流是<b>边到边处理</b>的，
     * 攒完再返回就失去了流式的意义。
     */
    public interface ChatStreamHandler {

        /** 检索结果（流的第一个事件）。先推给前端，用户能立刻看到参考了哪几段 */
        void onSources(List<Source> sources, boolean hasContext, boolean contextTruncated);

        /** 一段增量文本，可能有几十上百个 */
        void onDelta(String text);

        /** 生成完成。{@code hasAnswer = false} 表示模型判定资料答不了（返回了兜底话术） */
        void onDone(String answer, boolean hasAnswer);

        /**
         * 出错 —— <b>它代替 {@code onDone}，不会两个都来</b>。
         * <p>
         * ⚠️ 可能是「吐到一半才失败」，此时前面的 delta 已经推出去了，
         * 调用方要让用户知道「已输出的部分可能不完整」。
         */
        void onError(int code, String message);
    }

    // ---------- 报文结构 ----------

    /** Python 侧统一响应包装：{@code {code, message, data}} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiEnvelope<T>(Integer code, String message, T data) {
    }

    /** 一轮历史消息 */
    public record HistoryItem(String role, String content) {
    }

    public record ChatRequest(String userId,
                              String knowledgeBaseId,
                              String conversationId,
                              String query,
                              Integer topK,
                              Boolean enableRerank,
                              /**
                               * 回答风格（sys_prompt 的版本行 id）。
                               * <p>
                               * 可空 = 用管理员设的默认风格。Python 侧找不到这条
                               * （被下线了 / 不可选）时也会回退默认，不会让提问失败。
                               */
                              String promptId,
                              List<HistoryItem> history) {
    }

    /** 对话结果 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatData(String answer,
                           List<Source> sources,
                           Boolean hasContext,
                           Boolean hasAnswer,
                           Boolean contextTruncated) {
    }

    /** 引用来源。字段名对齐 Python 侧的 camelCase */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String chunkId,
                         String documentId,
                         String knowledgeBaseId,
                         Integer chunkIndex,
                         String content,
                         Double score) {
    }

    public record SearchRequest(String userId,
                                String knowledgeBaseId,
                                String query,
                                Integer topK,
                                Boolean enableRerank) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchData(List<Source> results, Integer total, Boolean reranked) {
    }

    // ---------- 状态探测 ----------

    public record StatusData(Boolean llmConfigured, String configSource) {
    }

    // ---------- 简历解析 ----------

    public record ResumeParseRequest(String userId,
                                     String resumeId,
                                     String fileUrl,
                                     String fileType,
                                     String targetPosition) {
    }

    /**
     * 简历解析结果。
     * <p>
     * {@code structured} 用 {@link JsonNode} 而不是 String：它就是一段嵌套 JSON，
     * 原样存进 {@code resume.structured_json}（JSON 列）即可，
     * 当 String 收反而要再拼一次转义。
     * <p>
     * {@code rawText} / {@code pageCount} 是文本提取的产物，Java 侧自己取不到，
     * 所以必须由 Python 回传（见 {@link #parseResume} 的说明）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResumeParseData(String rawText,
                                  Integer pageCount,
                                  String summary,
                                  JsonNode structured,
                                  List<Skill> skills,
                                  Boolean truncated) {

        /**
         * 单项技能。
         * <p>
         * 名字就叫 {@code Skill} 而不是 {@code ResumeSkill} —— 后者是本项目
         * {@code pojo.entity.ResumeSkill}（数据库实体）的名字，重名会让调用方
         * 不得不用全限定名区分，读起来很别扭。
         * <p>
         * 字段名对齐 Python 侧 {@code resume._normalize_skills}。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Skill(String name, String type, String proficiency, Double years) {
        }
    }

    // ---------- 职位 JD 解析 ----------

    public record JobParseRequest(String userId, String jobId, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobParseData(String summary,
                               JsonNode structured,
                               List<Skill> skills,
                               Boolean truncated) {

        /** 字段名对齐 Python 侧 {@code job._normalize_skills} */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Skill(String name, String type, String requiredLevel, Boolean required) {
        }
    }

    // ---------- 简历优化建议 ----------

    /**
     * 简历分析请求。
     * <p>
     * ⚠️ 传的是<b>文本</b>而不是 resumeId —— Python 不连数据库（数据访问全在 Java），
     * 所以 {@code resume.raw_content} / {@code job.raw_content} 由调用方带过去。
     */
    public record ResumeAnalyzeRequest(String userId,
                                       String resumeId,
                                       String resumeText,
                                       String jobText,
                                       String target,
                                       String knowledgeBaseId) {
        public ResumeAnalyzeRequest(String userId,
                                    String resumeId,
                                    String resumeText,
                                    String jobText,
                                    String target) {
            this(userId, resumeId, resumeText, jobText, target, null);
        }
    }

    /**
     * 简历分析结果。
     * <p>
     * 字段名对齐 Python 侧 {@code resume._normalize_analysis}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResumeAnalyzeData(Double overallScore,
                                    String summary,
                                    List<String> highlights,
                                    List<AnalyzeItem> items,
                                    Boolean truncated) {

        /**
         * 单条建议。
         * <p>
         * ★ {@code needsFact} 是本项目最重要的一个布尔：
         * <ul>
         *   <li>{@code false} —— 只是表达问题，{@code rewritten} 是改写后的句子，事实没变</li>
         *   <li>{@code true} —— 这里缺事实，{@code rewritten} 一定是 null，
         *       由 {@code missingFact} 说清要用户补什么</li>
         * </ul>
         * Python 侧已经保证了这个不变量（needsFact 为真时强制清空 rewritten），
         * Java 这边只需如实透传。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AnalyzeItem(String target,
                                  String original,
                                  String issue,
                                  String suggestion,
                                  String rewritten,
                                  Boolean needsFact,
                                  String missingFact) {
        }
    }

    // ---------- 简历-JD 匹配 ----------

    /**
     * 匹配请求。
     * <p>
     * ⚠️ 传的是<b>两段文本</b>（简历原文 + JD 原文）—— 本服务不连数据库，
     * Java 把已经存好的 {@code resume.raw_content} / {@code job.raw_content} 带过来。
     */
    public record ResumeMatchRequest(String userId,
                                     String resumeId,
                                     String jobId,
                                     String resumeText,
                                     String jobText) {
    }

    /**
     * 薪酬估算入参。
     * <p>
     * {@code userId} 只用于 Python 侧的用量记账（scene = SALARY_ESTIMATE），
     * 不参与估算本身。
     */
    public record SalaryEstimateRequest(String userId,
                                        String city,
                                        String position,
                                        Integer yearsOfExperience,
                                        String education,
                                        String companyType,
                                        List<String> skills,
                                        BigDecimal currentAnnualSalary) {
    }

    /**
     * 薪酬估算结果（Python 侧输出，camelCase）。
     * <p>
     * ★ 金额单位是<b>元</b>，不是 K、也不是万 —— 单位换算交给前端，
     * 后端存一个明确的数比存「25.5」再靠约定猜单位可靠。
     * <p>
     * {@code confidence} 是模型自评，服务端必须再钳一次（见 SalaryEstimateServiceImpl）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SalaryEstimateData(Double monthlyP50,
                                     Double monthlyP75,
                                     Double monthlyP90,
                                     Double annualP50,
                                     Double annualP75,
                                     Double annualP90,
                                     Integer monthsPerYear,
                                     String confidence,
                                     List<String> basis,
                                     List<String> factors) {
    }

    /**
     * 匹配结果。
     * <p>
     * 字段名对齐 Python 侧 {@code match._normalize}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResumeMatchData(Double overallScore,
                                  String summary,
                                  List<MatchItem> items,
                                  List<String> matchedSkills,
                                  List<String> missingSkills,
                                  List<String> matchedExperience,
                                  List<String> missingExperience,
                                  List<String> suggestions,
                                  Boolean truncated) {

        /**
         * 逐条要求的匹配结论。
         * <p>
         * ★ {@code verdict} 是五类之一，见 {@code ResumeMatchVO.MatchItemVO} 的说明。
         * <p>
         * ⚠️ Python 侧保证了一条不变量：<b>{@code verdict = MATCHED} 时
         * {@code evidence} 一定非空</b>（没有依据就不许说「已匹配」，
         * 会被降级成 UNCERTAIN）。Java 这边如实透传即可。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record MatchItem(String category,
                                String requirement,
                                String verdict,
                                String evidence,
                                String advice) {
        }
    }

    // ---------- AI 面试 ----------
    public record InterviewGenerateRequest(String userId,
                                           String interviewId,
                                           String interviewType,
                                           String difficulty,
                                           Integer questionCount,
                                           String resumeText,
                                           String jobText,
                                           String knowledgeBaseId) {
        public InterviewGenerateRequest(String userId,
                                        String interviewId,
                                        String interviewType,
                                        String difficulty,
                                        Integer questionCount,
                                        String resumeText,
                                        String jobText) {
            this(userId, interviewId, interviewType, difficulty, questionCount, resumeText, jobText, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterviewGenerateData(List<Question> questions) {

        /**
         * 单道题。
         * <p>
         * {@code questionNo} 由 Python 侧重排成 1..N（不信模型给的编号），
         * 所以这里直接用它当 {@code interview_question.question_no}。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Question(Integer questionNo,
                               String questionType,
                               String question,
                               List<String> expectedPoints,
                               String difficulty) {
        }
    }

    public record InterviewAnalyzeRequest(String userId,
                                          String interviewId,
                                          String question,
                                          String answer,
                                          List<String> expectedPoints,
                                          String interviewType) {
    }

    /**
     * 单题评分结果。
     * <p>
     * 字段名对齐 Python 侧 {@code interview._normalize_analysis}。
     * 三个分数都是 <b>0~100</b>（不是文档示例里的 0~1）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterviewAnalyzeData(Double score,
                                       Technical technical,
                                       Communication communication,
                                       List<String> missingPoints,
                                       String comment,
                                       String followUp) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Technical(Double score, List<String> strengths, List<String> weaknesses) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Communication(Double clarity, List<String> issues) {
        }
    }

    public record InterviewReportRequest(String userId,
                                         String interviewId,
                                         String interviewType,
                                         String jobTitle,
                                         List<ReportItem> items) {

        /**
         * 报告里的单题记录。
         * <p>
         * {@code analysis} 用 {@link JsonNode} —— 它就是 {@code interview_question.ai_analysis}
         * 里存的那段 JSON，原样取出带过去，不用反序列化再序列化一遍。
         */
        public record ReportItem(Integer questionNo,
                                 String question,
                                 String answer,
                                 Double score,
                                 JsonNode analysis) {
        }
    }

    /**
     * 复盘报告。
     * <p>
     * 字段名对齐 Python 侧 {@code interview._normalize_report}，
     * 也直接对齐 {@code interview_report} 表的列 —— 调用方拿到就能落库。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterviewReportData(Double overallScore,
                                      Double technicalScore,
                                      Double communicationScore,
                                      Double problemSolvingScore,
                                      // 工程规范 / 岗位契合：都可空，且允许只给其中一个。
                                      // 老版本 Python 不返回这两个 key —— @JsonIgnoreProperties 兜着，不会是 null 之外的麻烦
                                      Double engineeringScore,
                                      Double jobFitScore,
                                      List<String> strengths,
                                      List<String> weaknesses,
                                      List<String> suggestions,
                                      String reportText) {
    }

    // ---------- 网页正文提取工具 ----------
    public record UrlExtractRequest(String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UrlExtractData(String url, String title, String content, Integer charCount) {
    }

    // ---------- 面试邀约抽取 ----------

    /**
     * 邀约抽取入参。
     * <p>
     * ★ {@code text} 与 {@code fileType} + {@code contentBase64} <b>二选一</b>：
     * 粘贴文本时只填前者，上传文件时只填后者。Python 侧按哪个非空走哪条解析路径。
     * <p>
     * {@code userId} 只用于 Python 侧的用量记账，不参与抽取本身。
     */
    public record InvitationExtractRequest(String userId,
                                           String text,
                                           String fileType,
                                           String contentBase64) {
    }

    /**
     * 邀约抽取结果。
     * <p>
     * ⚠️ {@code sourceText} 是<b>解析后的正文</b>（文件输入时 Java 手上没有，
     * 只能由 Python 回传），Java 用它做证据校验，<b>不返回给前端</b>。
     *
     * @param events    抽出的候选日程，可能是空数组
     * @param truncated 原文过长被截断时为 true
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvitationExtractData(String sourceText,
                                        List<ExtractedEvent> events,
                                        Boolean truncated) {
    }

    /**
     * 单条候选日程。
     * <p>
     * ★ <b>所有字段都可为 null</b>，null = 原文没写，Python 侧<b>禁止填默认值</b>
     * （「面试」两个字不等于技术一面）。
     * <p>
     * ★ {@code evidence} 是「字段名 → 支撑该值的原文片段」。Java 侧会逐项校验
     * {@code 原文.contains(片段)}，不过的字段直接丢弃 —— 所以这里的字段
     * 不是「模型说了算」，只是「模型的说法」。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedEvent(String title,
                                 String companyName,
                                 String positionName,
                                 String eventType,
                                 String eventAt,
                                 Boolean eventAtDerived,
                                 Integer durationMinutes,
                                 String eventMode,
                                 String location,
                                 String meetingUrl,
                                 String interviewer,
                                 String note,
                                 Map<String, String> evidence) {
    }

    // ---------- AI 配置快照 ----------

    /**
     * 当前生效的 AI 配置（Python 侧 {@code ai_config.snapshot()} 的原样结构）。
     * <p>
     * ⚠️ {@code refreshedAt} 是 <b>ISO 带时区</b>的字符串（{@code +08:00}），
     * 刻意当 {@code String} 透传而不转 {@code OffsetDateTime} ——
     * 它是给人看的「上次同步时间」，转一道只会多一次格式化，
     * 而且全局时间格式约定（{@code yyyy-MM-ddTHH:mm:ss}）不带偏移，
     * 把偏移吃掉反而丢信息。
     *
     * @param scenes      每个用途一条，<b>没配的也会出现</b>（{@code source = "env"}）
     * @param refreshedAt 上一次成功拉取 Java 配置的时间；从没成功过为 null
     * @param lastError   上一次拉取失败的原因，成功时 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EffectiveConfigData(List<SceneConfig> scenes,
                                      String refreshedAt,
                                      String lastError) {
    }

    /**
     * 单个用途的生效配置。
     * <p>
     * ⚠️ 只有 {@code hasKey} 布尔，<b>没有 apiKey</b> —— Python 侧也不返回，
     * 这里当然也就没有可接的字段。别为了「方便排查」去加一个。
     *
     * @param scene   CHAT / EMBEDDING / RERANK
     * @param model   生效的模型名；空串表示「未配置」（RERANK 没有 .env 兜底）
     * @param source  {@code java} = 来自管理端配置；{@code env} = 回退 .env
     * @param baseUrl 生效的 base url
     * @param hasKey  有没有可用的 Key
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SceneConfig(String scene,
                              String model,
                              String source,
                              String baseUrl,
                              Boolean hasKey) {
    }
}
