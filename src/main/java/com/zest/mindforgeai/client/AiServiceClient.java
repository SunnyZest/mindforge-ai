package com.zest.mindforgeai.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.AiServiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

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
    private static final String PATH_CHAT = "/internal/ai/v1/rag/chat";
    private static final String PATH_SEARCH = "/internal/ai/v1/rag/search";

    private final AiServiceProperties properties;
    private final RestClient restClient;

    public AiServiceClient(AiServiceProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
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
        try {
            AiEnvelope<T> envelope = restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(typeRef);

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
            log.error("AI 服务返回异常状态 path={} status={} body={}",
                    path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BizException(ErrorCode.AI_SERVICE_ERROR,
                    "AI 服务返回异常状态：" + e.getStatusCode());

        } catch (Exception e) {
            log.error("调用 AI 服务出现未知错误 path={}", path, e);
            throw new BizException(ErrorCode.AI_SERVICE_ERROR, "调用 AI 服务失败");
        }
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
}
