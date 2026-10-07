package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.DocumentChunkMapper;
import com.zest.mindforgeai.mapper.DocumentMapper;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.entity.DocumentChunk;
import com.zest.mindforgeai.pojo.vo.ChunkVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.ChunkService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 文档分块查询实现。
 *
 * @author crx
 * @since 2026-10-07
 */
@Slf4j
@Service
public class ChunkServiceImpl implements ChunkService {

    /** 管理端分页大小上限 —— 分块正文可能很长，不限制会把响应撑爆 */
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    public ChunkServiceImpl(DocumentChunkMapper chunkMapper,
                            DocumentMapper documentMapper) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    @Override
    public ChunkVO getForCurrentUser(Long chunkId) {
        DocumentChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new BizException(ErrorCode.CHUNK_NOT_FOUND);
        }

        // ★ 归属校验
        //
        // 分块 id 是连续自增的数字，不校验的话用户把 URL 里的数字改一改，
        // 就能看到别人文档的内容。
        //
        // ⚠️ 校验失败时刻意抛「不存在」而不是「无权访问」：
        //    返回 403 等于告诉调用方「这个 id 是存在的，只是不归你」，
        //    相当于帮他确认了别人的数据范围。返回 404 什么都不泄漏。
        if (!Objects.equals(chunk.getUserId(), SecurityUtils.getUserId())) {
            log.warn("用户 {} 试图访问不属于自己的分块 {}（属于用户 {}）",
                    SecurityUtils.getUserId(), chunkId, chunk.getUserId());
            throw new BizException(ErrorCode.CHUNK_NOT_FOUND);
        }

        // 所属文档也要在（逻辑删除的文档，MP 的 selectById 会自动过滤掉）。
        // 文档删了但分块还留着的情况是存在的 —— 分块是物理删除、文档是逻辑删除，
        // 两者的清理时机不同步。
        Document doc = documentMapper.selectById(chunk.getDocumentId());
        if (doc == null) {
            throw new BizException(ErrorCode.CHUNK_NOT_FOUND);
        }

        return ChunkVO.of(chunk, doc);
    }

    @Override
    public PageResultVO<ChunkVO> pageByDocument(Long documentId, int page, int pageSize) {
        // 管理端不校验归属（管理员本来就能看所有人的文档），
        // 但要确认文档存在 —— 否则「文档 id 打错」会静默返回空列表，排查起来很费劲
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        IPage<DocumentChunk> result = chunkMapper.selectPage(
                new Page<>(safePage, safeSize),
                Wrappers.<DocumentChunk>lambdaQuery()
                        .eq(DocumentChunk::getDocumentId, documentId)
                        .orderByAsc(DocumentChunk::getChunkIndex));

        // 所有分块都属于同一个文档，doc 复用一次就行，不用每条都查
        return PageResultVO.of(result,
                result.getRecords().stream().map(c -> ChunkVO.of(c, doc)).toList());
    }
}
