package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档 Mapper
 *
 * @author crx
 * @since 2026-10-05
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
