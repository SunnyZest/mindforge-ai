package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zest.mindforgeai.mapper.SysPromptMapper;
import com.zest.mindforgeai.pojo.entity.SysPrompt;
import com.zest.mindforgeai.pojo.vo.PromptOptionVO;
import com.zest.mindforgeai.service.PromptQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Service
public class PromptQueryServiceImpl implements PromptQueryService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final int YES = 1;

    private final SysPromptMapper promptMapper;

    public PromptQueryServiceImpl(SysPromptMapper promptMapper) {
        this.promptMapper = promptMapper;
    }

    @Override
    public List<PromptOptionVO> listSelectable(String scene) {
        if (!StringUtils.hasText(scene)) {
            return List.of();
        }

        List<SysPrompt> prompts = promptMapper.selectList(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getScene, scene.trim().toUpperCase(Locale.ROOT))
                // 只有已发布的才对外 —— 草稿是管理员还没定稿的东西
                .eq(SysPrompt::getStatus, PUBLISHED)
                // 只有管理员标了「允许用户切换」的才暴露。8 个 prompt 里有 7 个的输出格式
                // 是内部契约（resume_parse 必须吐特定 JSON 等），让用户切会直接让功能挂掉。
                .eq(SysPrompt::getUserSelectable, YES)
                .orderByDesc(SysPrompt::getIsDefault)
                .orderByAsc(SysPrompt::getId));

        return prompts.stream().map(prompt -> {
            PromptOptionVO option = new PromptOptionVO();
            option.setId(String.valueOf(prompt.getId()));
            // label 在开放切换时是必填的（见 AdminPromptServiceImpl.create），
            // 这里再兜一次：万一历史数据没填，至少不要给用户显示 null
            option.setLabel(StringUtils.hasText(prompt.getLabel())
                    ? prompt.getLabel() : prompt.getName());
            option.setDescription(prompt.getDescription());
            option.setIsDefault(Integer.valueOf(YES).equals(prompt.getIsDefault()));
            return option;
        }).toList();
    }
}
