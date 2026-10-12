package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.AdminKnowledgeBaseVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * 管理端知识库管理（跨用户）。
 * <p>
 * 与用户端的 {@link KnowledgeBaseService} <b>刻意分成两个服务</b>：
 * 那个的每个方法都绑死了「归属校验」（{@code getOwned} 之类），
 * 给它加一个「管理员跳过校验」的开关，一旦哪天传错参数，
 * 用户端接口就会漏出别人的数据。分开写，各自只管自己那套规则。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface AdminKnowledgeService {

    /**
     * 知识库分页列表（所有用户）。
     *
     * @param keyword 名称模糊匹配，可空
     * @param status  状态筛选，可空
     * @param userId  只看某个用户的，可空
     */
    PageResultVO<AdminKnowledgeBaseVO> pageKnowledgeBases(long page, long pageSize,
                                                          String keyword, Integer status, Long userId,
                                                          String sortBy, String sortOrder);

    /** 知识库详情（含归属人） */
    AdminKnowledgeBaseVO getDetail(Long id);

    /**
     * 创建官方/管理员知识库。
     */
    AdminKnowledgeBaseVO createKnowledgeBase(com.zest.mindforgeai.pojo.dto.KnowledgeBaseCreateDTO dto);

    /**
     * 更新知识库信息。
     */
    void updateKnowledgeBase(Long id, com.zest.mindforgeai.pojo.dto.KnowledgeBaseUpdateDTO dto);

    /**
     * 删除知识库（级联逻辑删除其下文档与对话）。
     * <p>
     * 与用户端删自己的库走同一套级联规则（{@link KnowledgeBaseCascadeService}）。
     */
    void delete(Long id);
}
