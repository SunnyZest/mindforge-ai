package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.ChunkVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * 文档分块查询。
 * <p>
 * 分块的<b>写入</b>在内部接口（给 AI 服务回写用），这里只负责<b>读</b>。
 *
 * @author crx
 * @since 2026-10-07
 */
public interface ChunkService {

    /**
     * 按 id 查一个分块 —— 给用户端的引用溯源用。
     * <p>
     * <b>必须校验归属</b>：分块 id 是连续数字，不校验的话用户改个数字
     * 就能看到别人的文档内容。所以这里会确认
     * {@code chunk.userId == 当前登录用户}，并且所属文档没被删除。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         分块不存在 / 不属于当前用户 / 所属文档已删除
     */
    ChunkVO getForCurrentUser(Long chunkId);

    /**
     * 分页查某个文档的分块 —— 给管理端排查用。
     * <p>
     * 管理端不校验归属（管理员本来就能看所有人的文档），
     * 但要确认文档存在，否则「文档 id 打错」会静默返回空列表。
     */
    PageResultVO<ChunkVO> pageByDocument(Long documentId, int page, int pageSize);
}
