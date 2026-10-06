package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档分块 Mapper
 *
 * @author crx
 * @since 2026-10-06
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 物理删除某个文档的全部分块。
     * <p>
     * {@code document_chunk} 没有 {@code deleted} 列，只能物理删除。
     * 用途：重写分块前先清空 —— 保证「先删后插」的幂等语义，
     * 避免重复处理时撞 {@code uk_doc_chunk_index} 唯一索引。
     */
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 批量插入分块。
     * <p>
     * 用一条 INSERT 多 VALUES 而不是循环单条插入 —— 一篇文档几百个分块，
     * 循环插入会有几百次数据库往返。
     */
    int batchInsert(@Param("list") List<DocumentChunk> list);
}
