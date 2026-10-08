package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysAiModel;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 模型配置 Mapper
 * <p>
 * 只用 MyBatis-Plus 自带的 CRUD，没有自定义 SQL。
 *
 * @author crx
 * @since 2026-10-08
 */
@Mapper
public interface SysAiModelMapper extends BaseMapper<SysAiModel> {
}
