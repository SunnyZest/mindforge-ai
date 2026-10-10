package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.PromptOptionVO;

import java.util.List;

/**
 * 用户端：可选的回答风格。
 *
 * <p>只回「有哪些风格」—— <b>不回提示词正文</b>。原文里有防虚构规则和输出格式约定，
 * 暴露出去等于把提示词送人，也容易被拿来绕过约束。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface PromptQueryService {

    /**
     * 列出某个场景下用户可选的风格（已发布 + 管理员标记了可选的）。
     *
     * @return 默认的排最前；一条都没配时返回空列表（前端就不显示切换入口）
     */
    List<PromptOptionVO> listSelectable(String scene);
}
