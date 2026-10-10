package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.PromptRuntimeVO;

import java.util.List;

/**
 * 把提示词交给 Python 服务。
 *
 * <p>提示词正文在 Java（管理端维护），但真正渲染它的是 Python ——
 * 所以要把已发布的版本下发过去。这和 AI 模型配置那条通道是同一个模式。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface InternalPromptService {

    /**
     * 取所有已发布的提示词（含正文）。
     *
     * @return 默认的排前面，方便 Python 侧「同 scene 取第一条」；一条都没配时返回空列表
     */
    List<PromptRuntimeVO> listPublished();
}
