package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.KnowledgeBaseMapper;
import com.zest.mindforgeai.pojo.dto.KnowledgeBaseCreateDTO;
import com.zest.mindforgeai.pojo.dto.KnowledgeBaseUpdateDTO;
import com.zest.mindforgeai.pojo.entity.KnowledgeBase;
import com.zest.mindforgeai.pojo.vo.KnowledgeBaseStatsVO;
import com.zest.mindforgeai.pojo.vo.KnowledgeBaseVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.KnowledgeBaseCascadeService;
import com.zest.mindforgeai.service.KnowledgeBaseService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库服务实现
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseCascadeService cascadeService;
    private final AuditService auditService;

    public KnowledgeBaseServiceImpl(KnowledgeBaseCascadeService cascadeService,
                                    AuditService auditService) {
        this.cascadeService = cascadeService;
        this.auditService = auditService;
    }

    @Override
    public PageResultVO<KnowledgeBaseVO> pageKnowledgeBases(long page, long pageSize,
                                                            String keyword, Integer status) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        // 用自定义查询而不是 MP 的 wrapper：
        // 表里的 document_count 冗余字段无人维护（永远是 0），这里改成实时 COUNT，
        // 保证列表和 stats 接口的口径一致。
        // 归属校验（user_id）也写在 SQL 里，不依赖上层。
        IPage<KnowledgeBase> result = baseMapper.selectPageWithDocumentCount(
                new Page<>(safePage, safeSize),
                SecurityUtils.getUserId(),
                keyword,
                status);

        return PageResultVO.of(result,
                result.getRecords().stream().map(KnowledgeBaseVO::of).toList());
    }

    @Override
    public KnowledgeBaseVO getDetail(Long id) {
        return KnowledgeBaseVO.of(requireOwned(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseVO create(KnowledgeBaseCreateDTO dto) {
        KnowledgeBase kb = new KnowledgeBase()
                .setUserId(SecurityUtils.getUserId())
                .setName(dto.getName())
                .setDescription(dto.getDescription())
                .setStatus(1)
                .setDocumentCount(0);
        save(kb);

        log.info("创建知识库 id={} name={}", kb.getId(), kb.getName());
        auditService.recordSuccess(AuditConstants.ACTION_KB_CREATE,
                AuditConstants.RESOURCE_KNOWLEDGE_BASE, String.valueOf(kb.getId()),
                Map.of("name", kb.getName()));

        return KnowledgeBaseVO.of(kb);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, KnowledgeBaseUpdateDTO dto) {
        // 先根据id查询到对应知识库，主要是审计用
        KnowledgeBase kb = requireOwned(id);

        // MP 默认策略：null 字段不进 UPDATE → 天然支持局部更新
        updateById(new KnowledgeBase()
                .setId(id)
                .setName(dto.getName())
                .setDescription(dto.getDescription())
                .setStatus(dto.getStatus())
                .setUpdatedAt(LocalDateTime.now()));

        log.info("修改知识库 id={}", id);

        // LinkedHashMap 而不是 Map.of —— 后者不允许 null 值
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("beforeName", kb.getName());
        detail.put("afterName", dto.getName());
        detail.put("beforeStatus", kb.getStatus());
        detail.put("afterStatus", dto.getStatus());
        auditService.recordSuccess(AuditConstants.ACTION_KB_UPDATE,
                AuditConstants.RESOURCE_KNOWLEDGE_BASE, String.valueOf(id), detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        KnowledgeBase kb = requireOwned(id);

        // 级联清理交给共享组件 —— 管理端删别人的库走的是同一套规则，
        // 两边各写一遍的话，将来加关联表时很容易只改一处，留下孤儿数据。
        KnowledgeBaseCascadeService.CascadeResult result = cascadeService.deleteCascade(id);

        log.info("删除知识库 id={} name={}", id, kb.getName());

        auditService.recordSuccess(AuditConstants.ACTION_KB_DELETE,
                AuditConstants.RESOURCE_KNOWLEDGE_BASE, String.valueOf(id),
                Map.of("name", kb.getName(),
                       "documentsRemoved", result.documentsRemoved(),
                       "conversationsRemoved", result.conversationsRemoved()));
    }

    @Override
    public KnowledgeBaseStatsVO getStats(Long id) {
        requireOwned(id);
        return baseMapper.selectStats(id);
    }

    @Override
    public KnowledgeBase getOwned(Long id) {
        return requireOwned(id);
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 取知识库并校验归属。
     * <p>
     * 不存在 → 40403；存在但不属于当前用户 → 40302。
     * 这里刻意区分两种错误，便于前端排查；知识库 id 是自增的，
     * 「存在性」本身不算敏感信息（知道了也访问不到）。
     */
    private KnowledgeBase requireOwned(Long id) {
        KnowledgeBase kb = getById(id);
        if (kb == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!kb.getUserId().equals(SecurityUtils.getUserId())) {
            // ⚠️ 刻意抛「不存在」而不是「无权访问」：
            // 抛 403 等于告诉调用方「这个 id 存在，只是不归你」，
            // 相当于帮他确认了别人的数据范围。抛 404 什么都不泄漏。
            throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }
}
