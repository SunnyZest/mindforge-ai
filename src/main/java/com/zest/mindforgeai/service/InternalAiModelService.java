package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.AiModelRuntimeVO;

import java.util.List;

/**
 * 把 AI 模型配置交给 Python 服务。
 *
 * <p>管理端维护的是 {@code sys_ai_model}（Java 加密存 Key），但真正调模型的是
 * Python 服务 —— 之前它只读自己的 {@code .env}，所以「管理端改了配置没有任何效果」。
 * 这个服务就是补上那半截。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface InternalAiModelService {

    /**
     * 取所有<b>启用</b>的模型配置，API Key 已解密。
     *
     * <p>同 scene 多条时按「默认优先、id 升序」返回，由调用方决定怎么取舍。
     *
     * @return 运行时配置；没有任何配置时返回空列表（Python 那边会回退环境变量）
     */
    List<AiModelRuntimeVO> listEnabled();
}
