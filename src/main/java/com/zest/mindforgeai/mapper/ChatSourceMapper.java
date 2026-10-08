package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.ChatSource;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回答引用来源 Mapper。
 * <p>
 * 记录「这条回答引用了哪些分块」—— 前端点引用时靠它定位到原文，
 * 也是评估检索质量的原始数据。
 * <p>
 * ⚠️ 同样没有 deleted 列，跟消息一起物理删除。
 *
 * @author crx
 * @since 2026-10-08
 */
@Mapper
public interface ChatSourceMapper extends BaseMapper<ChatSource> {
}
