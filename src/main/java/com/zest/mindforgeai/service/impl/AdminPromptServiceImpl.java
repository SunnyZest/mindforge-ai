package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.SysPromptMapper;
import com.zest.mindforgeai.pojo.dto.PromptCreateDTO;
import com.zest.mindforgeai.pojo.dto.PromptUpdateDTO;
import com.zest.mindforgeai.pojo.entity.SysPrompt;
import com.zest.mindforgeai.pojo.vo.PromptGroupVO;
import com.zest.mindforgeai.pojo.vo.PromptVO;
import com.zest.mindforgeai.service.AdminPromptService;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Service
public class AdminPromptServiceImpl implements AdminPromptService {

    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String ARCHIVED = "ARCHIVED";

    private static final int YES = 1;
    private static final int NO = 0;

    private final SysPromptMapper promptMapper;
    private final AuditService auditService;

    public AdminPromptServiceImpl(SysPromptMapper promptMapper, AuditService auditService) {
        this.promptMapper = promptMapper;
        this.auditService = auditService;
    }

    @Override
    public List<PromptGroupVO> listGroups(String scene) {
        // ⚠️ 不能写成 .eq(StringUtils.hasText(scene), SysPrompt::getScene, normalizeScene(scene)) ——
        //    Java 会【先算完所有参数】再调用 eq()，所以条件为 false 时 normalizeScene(null)
        //    照样执行，直接 NPE。带条件的 eq 里放任何「必须先判空」的表达式都要先在外面算好。
        String sceneFilter = StringUtils.hasText(scene) ? normalizeScene(scene) : null;

        List<SysPrompt> all = promptMapper.selectList(new LambdaQueryWrapper<SysPrompt>()
                .eq(sceneFilter != null, SysPrompt::getScene, sceneFilter)
                .orderByAsc(SysPrompt::getScene)
                .orderByAsc(SysPrompt::getName)
                .orderByDesc(SysPrompt::getVersion));

        // 按 (scene, name) 分组。用 LinkedHashMap 保住上面 ORDER BY 的顺序。
        // ⚠️ 分隔符用 \u0000 —— 用 ":" 之类的话，变体名里刚好有那个字符就会串组。
        Map<String, List<SysPrompt>> grouped = new LinkedHashMap<>();
        for (SysPrompt prompt : all) {
            grouped.computeIfAbsent(prompt.getScene() + '\u0000' + prompt.getName(),
                    key -> new ArrayList<>()).add(prompt);
        }

        List<PromptGroupVO> groups = new ArrayList<>(grouped.size());
        for (List<SysPrompt> versions : grouped.values()) {
            groups.add(toGroup(versions));
        }
        return groups;
    }

    private PromptGroupVO toGroup(List<SysPrompt> versions) {
        // 列表已经按 version 倒序，所以第一条就是最新版
        SysPrompt latest = versions.get(0);

        // ★ 变体的「对外属性」以【已发布版本】为准 —— 草稿上改了 label 不该立刻影响用户看到的
        SysPrompt effective = versions.stream()
                .filter(p -> PUBLISHED.equals(p.getStatus()))
                .findFirst()
                .orElse(latest);

        PromptGroupVO group = new PromptGroupVO();
        group.setScene(latest.getScene());
        group.setName(latest.getName());
        group.setLabel(effective.getLabel());
        group.setUserSelectable(Integer.valueOf(YES).equals(effective.getUserSelectable()));
        group.setIsDefault(Integer.valueOf(YES).equals(effective.getIsDefault()));
        group.setPublishedId(PUBLISHED.equals(effective.getStatus())
                ? String.valueOf(effective.getId()) : null);
        group.setLatestVersion(versions.stream()
                .map(SysPrompt::getVersion)
                .max(Comparator.naturalOrder())
                .orElse(1));
        group.setVersions(versions.stream().map(PromptVO::of).toList());
        return group;
    }

    // ---------- 增 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptVO create(PromptCreateDTO dto) {
        String scene = normalizeScene(dto.getScene());
        String name = dto.getName().trim();
        boolean selectable = Boolean.TRUE.equals(dto.getUserSelectable());
        String label = trimToNull(dto.getLabel());

        // 用户看到的就是 label，所以开放切换时必须给它 —— 否则前端只能拿内部变体名去显示
        if (selectable && label == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "允许用户切换时必须填「选项名」—— 用户在下拉里看到的就是它");
        }

        int version = nextVersion(scene, name);
        LocalDateTime now = LocalDateTime.now();

        SysPrompt entity = new SysPrompt()
                .setScene(scene)
                .setName(name)
                .setVersion(version)
                .setContent(dto.getContent())
                .setStatus(DRAFT)
                .setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()) ? YES : NO)
                .setUserSelectable(selectable ? YES : NO)
                .setLabel(label)
                .setDescription(trimToNull(dto.getDescription()))
                .setCreatedBy(SecurityUtils.getUserId())
                .setCreatedAt(now)
                .setUpdatedAt(now);
        promptMapper.insert(entity);

        log.info("新建提示词版本 id={} scene={} name={} v{} userSelectable={}",
                entity.getId(), scene, name, version, selectable);
        auditService.recordSuccess(AuditConstants.ACTION_PROMPT_CREATE,
                AuditConstants.RESOURCE_PROMPT, String.valueOf(entity.getId()),
                Map.of("scene", scene, "name", name, "version", version));

        return PromptVO.of(entity);
    }

    private int nextVersion(String scene, String name) {
        SysPrompt latest = promptMapper.selectOne(new LambdaQueryWrapper<SysPrompt>()
                .eq(SysPrompt::getScene, scene)
                .eq(SysPrompt::getName, name)
                .orderByDesc(SysPrompt::getVersion)
                .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    // ---------- 改 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptVO update(Long id, PromptUpdateDTO dto) {
        SysPrompt existing = requireExists(id);

        // ★ 已发布的不能改 —— 这条看着不方便，但它是版本管理能成立的前提：
        //   允许原地改的话，回滚回去的「旧版」其实是被改过的内容，历史就不可信了。
        if (PUBLISHED.equals(existing.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "已发布的版本不能修改 —— 请新建一个版本再改");
        }

        boolean selectable = dto.getUserSelectable() == null
                ? Integer.valueOf(YES).equals(existing.getUserSelectable())
                : Boolean.TRUE.equals(dto.getUserSelectable());
        String label = dto.getLabel() == null ? existing.getLabel() : trimToNull(dto.getLabel());
        if (selectable && label == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "允许用户切换时必须填「选项名」");
        }

        promptMapper.update(null, new LambdaUpdateWrapper<SysPrompt>()
                .eq(SysPrompt::getId, id)
                .set(dto.getContent() != null, SysPrompt::getContent, dto.getContent())
                .set(dto.getUserSelectable() != null, SysPrompt::getUserSelectable, selectable ? YES : NO)
                .set(dto.getLabel() != null, SysPrompt::getLabel, label)
                .set(dto.getIsDefault() != null, SysPrompt::getIsDefault,
                        Boolean.TRUE.equals(dto.getIsDefault()) ? YES : NO)
                .set(dto.getDescription() != null, SysPrompt::getDescription,
                        trimToNull(dto.getDescription()))
                .set(SysPrompt::getUpdatedAt, LocalDateTime.now()));

        SysPrompt updated = requireExists(id);
        auditService.recordSuccess(AuditConstants.ACTION_PROMPT_UPDATE,
                AuditConstants.RESOURCE_PROMPT, String.valueOf(id),
                Map.of("scene", updated.getScene(), "name", updated.getName(),
                        "version", updated.getVersion()));
        return PromptVO.of(updated);
    }

    // ---------- 发布 / 回滚 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptVO publish(Long id) {
        return activate(id, AuditConstants.ACTION_PROMPT_PUBLISH, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptVO rollback(Long id) {
        return activate(id, AuditConstants.ACTION_PROMPT_ROLLBACK, true);
    }

    /**
     * 把某一版变成「当前生效」的那一版。
     *
     * <p>发布和回滚的实现几乎一样，区别只在校验 —— 所以合成一个方法，
     * 免得两边逻辑各改一半、行为不一致。
     *
     * @param requireArchived true = 回滚（只接受 ARCHIVED）；false = 发布（只不接受已发布的）
     */
    private PromptVO activate(Long id, String auditAction, boolean requireArchived) {
        SysPrompt target = requireExists(id);

        if (PUBLISHED.equals(target.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "这一版已经是已发布状态");
        }
        if (requireArchived && !ARCHIVED.equals(target.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "只有已归档的版本才能回滚；草稿请用「发布」");
        }

        // ① 同一变体下原来已发布的归档 —— 一个变体同时只能有一版生效
        int archived = promptMapper.update(null, new LambdaUpdateWrapper<SysPrompt>()
                .eq(SysPrompt::getScene, target.getScene())
                .eq(SysPrompt::getName, target.getName())
                .eq(SysPrompt::getStatus, PUBLISHED)
                .set(SysPrompt::getStatus, ARCHIVED)
                .set(SysPrompt::getUpdatedAt, LocalDateTime.now()));

        // ② 这一版上线
        promptMapper.update(null, new LambdaUpdateWrapper<SysPrompt>()
                .eq(SysPrompt::getId, id)
                .set(SysPrompt::getStatus, PUBLISHED)
                .set(SysPrompt::getUpdatedAt, LocalDateTime.now()));

        // ③ 默认标记：一个场景下只能有一个默认（只在【已发布】的里面算，
        //    草稿上的标记不影响用户，不用管）
        if (Integer.valueOf(YES).equals(target.getIsDefault())) {
            promptMapper.update(null, new LambdaUpdateWrapper<SysPrompt>()
                    .eq(SysPrompt::getScene, target.getScene())
                    .eq(SysPrompt::getStatus, PUBLISHED)
                    .ne(SysPrompt::getId, id)
                    .set(SysPrompt::getIsDefault, NO));
        }

        SysPrompt updated = requireExists(id);
        log.info("提示词 {} id={} scene={} name={} v{}（归档了 {} 版）",
                requireArchived ? "回滚" : "发布", id,
                updated.getScene(), updated.getName(), updated.getVersion(), archived);

        auditService.recordSuccess(auditAction, AuditConstants.RESOURCE_PROMPT, String.valueOf(id),
                Map.of("scene", updated.getScene(), "name", updated.getName(),
                        "version", updated.getVersion(), "archivedCount", archived));
        return PromptVO.of(updated);
    }

    // ---------- 删 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysPrompt existing = requireExists(id);

        if (PUBLISHED.equals(existing.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "不能删除正在生效的版本 —— 请先把别的版本发布或回滚上去");
        }

        // 这张表没有 deleted 列，物理删
        promptMapper.deleteById(id);

        log.info("删除提示词 id={} scene={} name={} v{} status={}",
                id, existing.getScene(), existing.getName(),
                existing.getVersion(), existing.getStatus());
        auditService.recordSuccess(AuditConstants.ACTION_PROMPT_DELETE,
                AuditConstants.RESOURCE_PROMPT, String.valueOf(id),
                Map.of("scene", existing.getScene(), "name", existing.getName(),
                        "version", existing.getVersion(), "status", existing.getStatus()));
    }

    // ---------- 内部 ----------

    private SysPrompt requireExists(Long id) {
        SysPrompt prompt = promptMapper.selectById(id);
        if (prompt == null) {
            throw new BizException(ErrorCode.PROMPT_NOT_FOUND);
        }
        return prompt;
    }

    /** 场景统一转大写 —— 存小写的话 Python 那边按大写找就永远找不到。 */
    private static String normalizeScene(String scene) {
        return scene.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
