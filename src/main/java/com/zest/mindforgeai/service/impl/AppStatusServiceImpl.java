package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.pojo.vo.AiStatusVO;
import com.zest.mindforgeai.service.AppStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 能力状态实现。
 *
 * @author crx
 * @since 2026-10-16
 */
@Slf4j
@Service
public class AppStatusServiceImpl implements AppStatusService {

    /** AI 服务连得上、模型密钥也配好了 */
    private static final String STATUS_READY = "READY";

    /** AI 服务活着，但模型没配全（缺 key）—— 所有 AI 功能都会报「未配置模型」 */
    private static final String STATUS_DEGRADED = "DEGRADED";

    /** AI 服务连不上 / 探测超时 */
    private static final String STATUS_OFFLINE = "OFFLINE";

    private final AiServiceClient aiClient;

    public AppStatusServiceImpl(AiServiceClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public AiStatusVO aiStatus() {
        AiStatusVO vo = new AiStatusVO();

        try {
            AiServiceClient.StatusData data = aiClient.status();
            boolean configured = Boolean.TRUE.equals(data.llmConfigured());

            vo.setStatus(configured ? STATUS_READY : STATUS_DEGRADED);
            vo.setLlmConfigured(configured);

            if (!configured) {
                log.warn("AI 服务在线但模型密钥未配置，所有 AI 功能都会失败");
            }
            // 配置来源只写日志，不返回给前端 —— 用户端不需要知道配置是管理端下发的还是环境变量兜底的
            log.debug("AI 状态探测：configured={} source={}", configured, data.configSource());

        } catch (Exception e) {
            // ★ 这里【绝对不能往外抛】。
            //   这是个给首页徽章用的探测接口：Python 挂了，首页必须照样能打开，
            //   只是徽章显示「离线」。往外抛的话，AI 服务一挂用户连首页都进不来 ——
            //   一个装饰性状态把整个产品拖下水，完全不成比例。
            log.warn("AI 服务状态探测失败，按离线处理：{}", e.getMessage());
            vo.setStatus(STATUS_OFFLINE);
            vo.setLlmConfigured(false);
        }

        return vo;
    }
}
