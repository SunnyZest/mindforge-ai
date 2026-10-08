package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.KnowledgeBaseMapper;
import com.zest.mindforgeai.pojo.vo.AdminKnowledgeBaseVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AdminKnowledgeService;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.KnowledgeBaseCascadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端知识库管理实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class AdminKnowledgeServiceImpl implements AdminKnowledgeService {

    /** 单页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseCascadeService cascadeService;
    private final AuditService auditService;

    public AdminKnowledgeServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper,
                                     KnowledgeBaseCascadeService cascadeService,
                                     AuditService auditService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.cascadeService = cascadeService;
        this.auditService = auditService;
    }

    @Override
    public PageResultVO<AdminKnowledgeBaseVO> pageKnowledgeBases(long page, long pageSize,
                                                                 String keyword, Integer status, Long userId) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        IPage<AdminKnowledgeBaseVO> result = knowledgeBaseMapper.selectAdminPage(
                new Page<>(safePage, safeSize), userId, keyword, status);

        return PageResultVO.of(result, result.getRecords());
    }

    @Override
    public AdminKnowledgeBaseVO getDetail(Long id) {
        return requireExists(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 先确认存在 —— 不存在要报 404，而不是静默「删除成功」。
        // 静默成功会让管理员以为删掉了，实际上什么都没发生。
        AdminKnowledgeBaseVO kb = requireExists(id);

        KnowledgeBaseCascadeService.CascadeResult result = cascadeService.deleteCascade(id);

        log.info("管理员删除知识库 id={} name={} owner={}，连带删除文档 {} 篇、对话 {} 个",
                id, kb.getName(), kb.getUsername(),
                result.documentsRemoved(), result.conversationsRemoved());

        // LinkedHashMap 而不是 Map.of —— 后者不允许 null 值，
        // 而 username 在「用户已被删」时可能是 null
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", kb.getName());
        detail.put("ownerId", kb.getUserId());
        detail.put("ownerUsername", kb.getUsername());
        detail.put("documentsRemoved", result.documentsRemoved());
        detail.put("conversationsRemoved", result.conversationsRemoved());
        // 标出这是管理员操作 —— 同一个 action 既有用户删自己的、也有管理员删别人的，
        // 事后追查时要能一眼分开
        detail.put("byAdmin", true);

        auditService.recordSuccess(AuditConstants.ACTION_KB_DELETE,
                AuditConstants.RESOURCE_KNOWLEDGE_BASE, String.valueOf(id), detail);
    }

    /* ---------- 内部工具 ---------- */

    private AdminKnowledgeBaseVO requireExists(Long id) {
        AdminKnowledgeBaseVO kb = knowledgeBaseMapper.selectAdminById(id);
        if (kb == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }
}
