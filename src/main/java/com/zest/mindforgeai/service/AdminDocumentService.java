package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.DocumentStatusUpdateDTO;
import com.zest.mindforgeai.pojo.vo.AdminDocumentVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * 管理端文档管理（跨用户）。
 * <p>
 * 与用户端的 {@link DocumentService} 分开，理由同 {@link AdminKnowledgeService}：
 * 那个服务的方法都绑定了归属校验，不能给用户端留「跳过校验」的开关。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface AdminDocumentService {

    /**
     * 文档分页列表（所有用户）。
     *
     * @param keyword         文件名模糊匹配，可空
     * @param knowledgeBaseId 按知识库筛选，可空
     * @param userId          按上传者筛选，可空
     * @param status          原始状态，匹配任一处理阶段（parse / chunk / vector），可空
     */
    PageResultVO<AdminDocumentVO> pageDocuments(long page, long pageSize, String keyword,
                                                Long knowledgeBaseId, Long userId, String status);

    /** 文档详情（含归属人与所属知识库名） */
    AdminDocumentVO getDetail(Long id);

    /**
     * 手工修改文档处理状态。
     * <p>
     * 运维用途：文档卡在某个状态（比如 Python 崩了没回写）时人工纠正。
     * <p>
     * ⚠️ 这是个<b>能破坏数据</b>的操作 —— 把没处理完的文档标成 SUCCESS，
     * 检索时就会拿到不完整的向量。所以它的权限码（{@code admin:document:update}）
     * 只给 SUPER_ADMIN，和「重试」区分开（重试只会重置为 PENDING，方向是安全的）。
     */
    void updateStatus(Long id, DocumentStatusUpdateDTO dto);

    /** 重试失败的文档（三个状态重置为 PENDING） */
    void retry(Long id);

    /** 删除文档（逻辑删除 document + sys_file，不动 OSS 对象） */
    void delete(Long id);
}
