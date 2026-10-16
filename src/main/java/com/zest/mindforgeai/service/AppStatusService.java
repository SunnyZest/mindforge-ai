package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.AiStatusVO;

/**
 * AI 能力状态。
 *
 * @author crx
 * @since 2026-10-16
 */
public interface AppStatusService {

    /** 探测 AI 服务与模型配置状态，返回 READY / DEGRADED / OFFLINE。 */
    AiStatusVO aiStatus();
}
