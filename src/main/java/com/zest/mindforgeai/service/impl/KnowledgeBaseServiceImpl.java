package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
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
import com.zest.mindforgeai.service.RecycleBinService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** 允许前端排序的字段（白名单）—— MyBatis-Plus 的排序是拼 SQL 的，
     *  不在白名单里的字段一律 40001，绝不把它拼进 SQL。
     *  值是【数据库列名】—— 这几个走自定义 XML 分页，排序由 MP 拼进 SQL。 */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "kb.name",
            "status", "kb.status",
            "createdAt", "kb.created_at",
            "updatedAt", "kb.updated_at");

    private final KnowledgeBaseCascadeService cascadeService;
    private final AuditService auditService;
    private final RecycleBinService recycleBinService;

    public KnowledgeBaseServiceImpl(KnowledgeBaseCascadeService cascadeService,
                                    AuditService auditService,
                                    RecycleBinService recycleBinService) {
        this.cascadeService = cascadeService;
        this.auditService = auditService;
        this.recycleBinService = recycleBinService;
    }

    @Override
    public PageResultVO<KnowledgeBaseVO> pageKnowledgeBases(long page, long pageSize,
                                                            String keyword, Integer status,
                                                            String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        // 用自定义查询而不是 MP 的 wrapper：
        // 表里的 document_count 冗余字段无人维护（永远是 0），这里改成实时 COUNT，
        // 保证列表和 stats 接口的口径一致。
        // 归属校验（user_id）也写在 SQL 里，不依赖上层。
        Page<KnowledgeBase> pageParam = new Page<>(safePage, safeSize);
        // ⚠️ XML 里【不能】再写 ORDER BY —— MP 会把这里的排序追加在后面，
        //    排在后面的字段完全不生效（第一个已经决定了顺序）。
        SortSupport.applyTo(pageParam, sortBy, sortOrder, SORTABLE, "kb.id", false);

        IPage<KnowledgeBase> result = baseMapper.selectPageWithDocumentCount(
                pageParam,
                SecurityUtils.getUserId(),
                SortSupport.escapeLike(keyword),
                status);

        return PageResultVO.of(result,
                result.getRecords().stream().map(KnowledgeBaseVO::of).toList());
    }

    @Override
    public KnowledgeBaseVO getDetail(Long id) {
        return KnowledgeBaseVO.of(requireAccessible(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseVO create(KnowledgeBaseCreateDTO dto) {
        KnowledgeBase kb = new KnowledgeBase()
                .setUserId(SecurityUtils.getUserId())
                .setName(dto.getName())
                .setDescription(dto.getDescription())
                .setStatus(1)
                .setIsSystem(0)
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
        if (Integer.valueOf(1).equals(kb.getIsSystem())) {
            throw new BizException(ErrorCode.FORBIDDEN, "官方智库由系统维护，普通用户无权修改");
        }

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
        if (Integer.valueOf(1).equals(kb.getIsSystem())) {
            throw new BizException(ErrorCode.FORBIDDEN, "官方智库由系统维护，普通用户无权删除");
        }

        // ★ 必须在级联删之前登记回收站：put 会让 KnowledgeBaseRecycleHandler 记下
        //   「即将被级联删掉的 documentIds / conversationIds」，删完再调就只剩空列表。
        // ⚠️ 管理端删库走的是 AdminKnowledgeService，不经过这里 —— 管理员删别人的库
        //    不该出现在用户的回收站里。
        recycleBinService.put(RecycleResourceType.KNOWLEDGE_BASE, id,
                kb.getUserId(), kb.getName());

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
        requireAccessible(id);
        return baseMapper.selectStats(id);
    }

    @Override
    public KnowledgeBase getOwned(Long id) {
        return requireOwned(id);
    }

    @Override
    public KnowledgeBase getAccessible(Long id) {
        return requireAccessible(id);
    }

    @Override
    public String resolveDualTrackKbIds(Long primaryKbId) {
        Set<Long> kbIds = new LinkedHashSet<>();
        if (primaryKbId != null && primaryKbId > 0) {
            KnowledgeBase primaryKb = getById(primaryKbId);
            if (primaryKb != null && Integer.valueOf(1).equals(primaryKb.getStatus())) {
                kbIds.add(primaryKbId);
            }
        }

        kbIds.addAll(enabledSystemKbIds());
        return joinKbIds(kbIds);
    }

    /**
     * 搜索页的检索范围解析。
     * <p>
     * 与 {@link #resolveDualTrackKbIds} 的区别只在「主库」怎么定：
     * <ul>
     *   <li>传了 primaryKbId → 就搜那个库（+ 官方智库），与聊天一致；</li>
     *   <li><b>没传</b>（搜索页的主用法）→ 搜「我自己的全部启用中知识库 + 官方智库」。
     *       用户不该为了搜一句话先想清楚内容在哪个库。</li>
     * </ul>
     * ⚠️ 只并自己的库，不碰别人的 —— 数据隔离的红线。
     */
    @Override
    public String resolveSearchScopeKbIds(Long primaryKbId) {
        Set<Long> kbIds = new LinkedHashSet<>();

        if (primaryKbId != null && primaryKbId > 0) {
            KnowledgeBase primaryKb = getById(primaryKbId);
            if (primaryKb != null && Integer.valueOf(1).equals(primaryKb.getStatus())) {
                kbIds.add(primaryKbId);
            }
        } else {
            List<KnowledgeBase> own = lambdaQuery()
                    .select(KnowledgeBase::getId)
                    .eq(KnowledgeBase::getUserId, SecurityUtils.getUserId())
                    .eq(KnowledgeBase::getIsSystem, 0)
                    .eq(KnowledgeBase::getStatus, 1)
                    .orderByAsc(KnowledgeBase::getId)
                    .list();
            own.forEach(kb -> kbIds.add(kb.getId()));
        }

        kbIds.addAll(enabledSystemKbIds());
        return joinKbIds(kbIds);
    }

    /**
     * 可用的官方智库 id（启用中 + 至少有一份向量化成功的文档）。
     * <p>
     * 没有「有 SUCCESS 文档」这个条件的话，会把空库也并进检索范围 ——
     * 白算一次、还可能让 Python 侧因为库不存在报错。
     */
    private List<Long> enabledSystemKbIds() {
        return lambdaQuery()
                .select(KnowledgeBase::getId)
                .eq(KnowledgeBase::getIsSystem, 1)
                .eq(KnowledgeBase::getStatus, 1)
                .apply("EXISTS (SELECT 1 FROM document d WHERE d.knowledge_base_id = knowledge_base.id AND d.deleted = 0 AND d.vector_status = 'SUCCESS')")
                .orderByAsc(KnowledgeBase::getId)
                .list()
                .stream()
                .map(KnowledgeBase::getId)
                .toList();
    }

    /** 拼成 Python 侧要的逗号分隔串；一个都没有时返回 null（调用方据此跳过检索） */
    private String joinKbIds(Set<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return null;
        }
        return kbIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 校验知识库访问权限：本人私有库 或 已启用的官方全局共享库。
     */
    private KnowledgeBase requireAccessible(Long id) {
        KnowledgeBase kb = getById(id);
        if (kb == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (kb.getUserId().equals(SecurityUtils.getUserId())
                || (Integer.valueOf(1).equals(kb.getIsSystem()) && Integer.valueOf(1).equals(kb.getStatus()))) {
            return kb;
        }
        throw new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    }

    /**
     * 取知识库并校验归属。
     * <p>
     * 不存在、或者存在但不属于当前用户，<b>都抛 40403</b>。
     * 刻意不区分两种错误：抛 403 等于告诉调用方「这个 id 存在，只是不归你」，
     * 相当于帮他确认了别人的数据范围。抛 404 什么都不泄漏。
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
