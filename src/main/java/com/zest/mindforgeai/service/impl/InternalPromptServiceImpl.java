package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zest.mindforgeai.mapper.SysPromptMapper;
import com.zest.mindforgeai.pojo.entity.SysPrompt;
import com.zest.mindforgeai.pojo.vo.PromptRuntimeVO;
import com.zest.mindforgeai.service.InternalPromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Service
public class InternalPromptServiceImpl implements InternalPromptService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final int YES = 1;

    private final SysPromptMapper promptMapper;

    public InternalPromptServiceImpl(SysPromptMapper promptMapper) {
        this.promptMapper = promptMapper;
    }

    @Override
    public List<PromptRuntimeVO> listPublished() {
        List<SysPrompt> prompts = promptMapper.selectList(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getStatus, PUBLISHED)
                .orderByAsc(SysPrompt::getScene)
                // 默认的排前面 —— Python 侧「同 scene 没指定就用第一条」
                .orderByDesc(SysPrompt::getIsDefault)
                .orderByAsc(SysPrompt::getId));

        List<PromptRuntimeVO> result = prompts.stream()
                .map(prompt -> new PromptRuntimeVO(
                        String.valueOf(prompt.getId()),
                        prompt.getScene(),
                        prompt.getName(),
                        prompt.getVersion(),
                        prompt.getLabel(),
                        Integer.valueOf(YES).equals(prompt.getIsDefault()),
                        Integer.valueOf(YES).equals(prompt.getUserSelectable()),
                        prompt.getContent()))
                .toList();

        // 只记条数和场景，不记正文（正文很长，刷日志没意义）
        log.info("向 AI 服务下发提示词 {} 条：{}", result.size(),
                result.stream().map(p -> p.scene() + "/" + p.name() + "v" + p.version()).toList());
        return result;
    }
}
