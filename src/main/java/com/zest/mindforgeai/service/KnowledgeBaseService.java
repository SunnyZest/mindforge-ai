package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.dto.KnowledgeBaseCreateDTO;
import com.zest.mindforgeai.pojo.dto.KnowledgeBaseUpdateDTO;
import com.zest.mindforgeai.pojo.entity.KnowledgeBase;
import com.zest.mindforgeai.pojo.vo.KnowledgeBaseStatsVO;
import com.zest.mindforgeai.pojo.vo.KnowledgeBaseVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;

/**
 * 知识库服务（用户端）。
 * <p>
 * <b>所有方法都只操作当前登录用户自己的知识库</b> —— 这是文档 §十五 的硬性要求：
 * 「资源访问必须校验 knowledge_base.user_id == 当前用户」。
 * 列表查询直接带上 user_id 条件；按 id 操作的一律先走归属校验。
 *
 * @author crx
 * @since 2026-10-05
 */
public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    /** 当前用户的知识库分页列表 */
    PageResultVO<KnowledgeBaseVO> pageKnowledgeBases(long page, long pageSize,
                                                     String keyword, Integer status,
                                                     String sortBy, String sortOrder);

    /** 知识库详情（校验归属） */
    KnowledgeBaseVO getDetail(Long id);

    /** 创建知识库（归属自动设为当前用户） */
    KnowledgeBaseVO create(KnowledgeBaseCreateDTO dto);

    /** 修改知识库（校验归属） */
    void update(Long id, KnowledgeBaseUpdateDTO dto);

    /** 删除知识库（校验归属），并级联逻辑删除其下文档与对话 */
    void delete(Long id);

    /** 知识库统计（校验归属） */
    KnowledgeBaseStatsVO getStats(Long id);

    /**
     * 取知识库并校验归属 —— 供其他业务模块复用（如文档模块上传前校验知识库是不是你的）。
     * <p>
     * 不存在 → 40403；存在但不属于当前用户 → 40302。
     */
    KnowledgeBase getOwned(Long id);
}
