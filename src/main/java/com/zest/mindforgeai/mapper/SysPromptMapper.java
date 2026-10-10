package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysPrompt;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提示词配置 Mapper。
 *
 * <p>没有自定义 SQL —— 查询条件都很简单，用 MP 的 LambdaQueryWrapper 就够，
 * 用不上 XML（本项目自定义 SQL 一律走 XML 的约定，针对的是「写 SQL 语句」这件事）。
 *
 * @author crx
 * @since 2026-10-10
 */
@Mapper
public interface SysPromptMapper extends BaseMapper<SysPrompt> {
}
