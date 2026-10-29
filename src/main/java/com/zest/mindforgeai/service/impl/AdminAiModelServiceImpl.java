package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.SysAiModelMapper;
import com.zest.mindforgeai.pojo.dto.AiModelCreateDTO;
import com.zest.mindforgeai.pojo.dto.AiModelUpdateDTO;
import com.zest.mindforgeai.pojo.entity.SysAiModel;
import com.zest.mindforgeai.pojo.vo.AiEffectiveConfigVO;
import com.zest.mindforgeai.pojo.vo.AiModelVO;
import com.zest.mindforgeai.service.AdminAiModelService;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.utils.AesCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端 AI 模型配置实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class AdminAiModelServiceImpl implements AdminAiModelService {

    private static final int ENABLED = 1;
    private static final int DEFAULT_FLAG = 1;
    private static final int NOT_DEFAULT = 0;

    private final SysAiModelMapper aiModelMapper;
    private final AesCipher aesCipher;
    private final AuditService auditService;
    private final AiServiceClient aiServiceClient;

    public AdminAiModelServiceImpl(SysAiModelMapper aiModelMapper,
                                   AesCipher aesCipher,
                                   AuditService auditService,
                                   AiServiceClient aiServiceClient) {
        this.aiModelMapper = aiModelMapper;
        this.aesCipher = aesCipher;
        this.auditService = auditService;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public List<AiModelVO> listModels(String scene) {
        List<SysAiModel> models = aiModelMapper.selectList(
                new LambdaQueryWrapper<SysAiModel>()
                        .eq(StringUtils.hasText(scene), SysAiModel::getScene, scene)
                        // 默认配置排前面，然后按 id 倒序（新建的在前）
                        .orderByAsc(SysAiModel::getScene)
                        .orderByDesc(SysAiModel::getIsDefault)
                        .orderByDesc(SysAiModel::getId));

        return models.stream().map(AiModelVO::of).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelVO create(AiModelCreateDTO dto) {
        boolean asDefault = Boolean.TRUE.equals(dto.getIsDefault());

        SysAiModel model = new SysAiModel()
                .setName(dto.getName())
                .setScene(dto.getScene())
                .setProvider(dto.getProvider())
                .setModelName(dto.getModelName())
                .setBaseUrl(dto.getBaseUrl())
                .setTemperature(dto.getTemperature())
                .setMaxTokens(dto.getMaxTokens())
                .setTimeoutSeconds(dto.getTimeoutSeconds())
                .setIsDefault(asDefault ? DEFAULT_FLAG : NOT_DEFAULT)
                .setStatus(dto.getStatus() == null ? ENABLED : dto.getStatus())
                .setDescription(dto.getDescription());

        // 加密必须在 save 之前 —— 明文永远不进数据库
        if (StringUtils.hasText(dto.getApiKey())) {
            model.setApiKeyCipher(aesCipher.encrypt(dto.getApiKey().trim()))
                    .setApiKeyHint(AesCipher.hintOf(dto.getApiKey()));
        }

        // ★ 先清后设。
        //   唯一索引 uk_ai_model_default 不允许同一 scene 同时存在两个「启用中的默认」，
        //   「先插再清」会在这两步之间撞上约束（insert 时旧默认还在）。
        //   清完再插中间会短暂「没有默认」，但两个操作在同一事务里，
        //   插入失败会连清除一起回滚 —— 不会真的留下没有默认的状态。
        if (asDefault) {
            clearOtherDefaults(model.getScene(), null);
        }

        aiModelMapper.insert(model);

        // ⚠️ 日志和审计里只出现 hint，绝不出现 Key
        log.info("新增 AI 模型配置 id={} scene={} provider={} model={} keyHint={}",
                model.getId(), model.getScene(), model.getProvider(),
                model.getModelName(), model.getApiKeyHint());

        auditService.recordSuccess(AuditConstants.ACTION_AI_CONFIG_CREATE,
                AuditConstants.RESOURCE_AI_CONFIG, String.valueOf(model.getId()),
                auditDetail(model, StringUtils.hasText(dto.getApiKey())));

        return AiModelVO.of(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelVO update(Long id, AiModelUpdateDTO dto) {
        SysAiModel existing = requireExists(id);

        LambdaUpdateWrapper<SysAiModel> wrapper = new LambdaUpdateWrapper<SysAiModel>()
                .eq(SysAiModel::getId, id)
                .set(dto.getName() != null, SysAiModel::getName, dto.getName())
                .set(dto.getScene() != null, SysAiModel::getScene, dto.getScene())
                .set(dto.getProvider() != null, SysAiModel::getProvider, dto.getProvider())
                .set(dto.getModelName() != null, SysAiModel::getModelName, dto.getModelName())
                .set(dto.getBaseUrl() != null, SysAiModel::getBaseUrl, dto.getBaseUrl())
                .set(dto.getTemperature() != null, SysAiModel::getTemperature, dto.getTemperature())
                .set(dto.getMaxTokens() != null, SysAiModel::getMaxTokens, dto.getMaxTokens())
                .set(dto.getTimeoutSeconds() != null,
                        SysAiModel::getTimeoutSeconds, dto.getTimeoutSeconds())
                .set(dto.getStatus() != null, SysAiModel::getStatus, dto.getStatus())
                .set(dto.getDescription() != null, SysAiModel::getDescription, dto.getDescription());

        // isDefault 是布尔语义，用 Boolean 判空 —— 传 false 要能把它取消掉，
        // 所以不能只判 null（false 也要写进 SQL）
        if (dto.getIsDefault() != null) {
            wrapper.set(SysAiModel::getIsDefault,
                    Boolean.TRUE.equals(dto.getIsDefault()) ? DEFAULT_FLAG : NOT_DEFAULT);
        }

        // apiKey 为空 = 保持原 Key 不变（不是清空）
        boolean apiKeyChanged = StringUtils.hasText(dto.getApiKey());
        if (apiKeyChanged) {
            wrapper.set(SysAiModel::getApiKeyCipher, aesCipher.encrypt(dto.getApiKey().trim()))
                    .set(SysAiModel::getApiKeyHint, AesCipher.hintOf(dto.getApiKey()));
        }

        // ★ 先清后设（理由同 create）。
        //   注意用的是「更新后的 scene」而不是入参 —— 这次可能同时改了用途，
        //   所以要先算出来再清。
        boolean makeDefault = dto.getIsDefault() != null && Boolean.TRUE.equals(dto.getIsDefault());
        if (makeDefault) {
            String targetScene = dto.getScene() != null ? dto.getScene() : existing.getScene();
            clearOtherDefaults(targetScene, id);
        }

        aiModelMapper.update(null, wrapper);

        SysAiModel updated = aiModelMapper.selectById(id);

        log.info("修改 AI 模型配置 id={} scene={} model={} 改了Key={} keyHint={}",
                id, updated.getScene(), updated.getModelName(),
                apiKeyChanged, updated.getApiKeyHint());

        Map<String, Object> detail = auditDetail(updated, apiKeyChanged);
        detail.put("beforeScene", existing.getScene());
        detail.put("beforeModelName", existing.getModelName());
        auditService.recordSuccess(AuditConstants.ACTION_AI_CONFIG_UPDATE,
                AuditConstants.RESOURCE_AI_CONFIG, String.valueOf(id), detail);

        return AiModelVO.of(updated);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysAiModel model = requireExists(id);

        // 物理删除 —— 连带把 Key 密文一起删掉
        aiModelMapper.deleteById(id);

        log.info("删除 AI 模型配置 id={} scene={} model={}",
                id, model.getScene(), model.getModelName());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", model.getName());
        detail.put("scene", model.getScene());
        detail.put("provider", model.getProvider());
        detail.put("modelName", model.getModelName());
        auditService.recordSuccess(AuditConstants.ACTION_AI_CONFIG_DELETE,
                AuditConstants.RESOURCE_AI_CONFIG, String.valueOf(id), detail);
    }

    /* ---------- 内部工具 ---------- */

    private SysAiModel requireExists(Long id) {
        SysAiModel model = aiModelMapper.selectById(id);
        if (model == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "AI 模型配置不存在");
        }
        return model;
    }

    /**
     * 把同一个 scene 下除 {@code keepId} 之外的默认标记清掉。
     * <p>
     * 「同一用途只有一个默认」现在是<b>双保险</b>：
     * <ul>
     *   <li>数据库层：唯一索引 {@code uk_ai_model_default}
     *       （函数索引，表达式为 NULL 的行不参与唯一性，所以多个「非默认」互不冲突）；</li>
     *   <li>代码层：本方法负责把旧的默认让出来 ——
     *       因为唯一索引是<b>立即生效</b>的（MySQL 不支持延迟约束），
     *       必须【先清后设】才能通过，不能反过来。</li>
     * </ul>
     *
     * @param keepId 保留哪一行的默认标记；传 {@code null} 表示「这个 scene 下的默认全清」
     *               （新增场景用：新行的 id 要 insert 之后才有）
     */
    private void clearOtherDefaults(String scene, Long keepId) {
        aiModelMapper.update(null, new LambdaUpdateWrapper<SysAiModel>()
                .eq(SysAiModel::getScene, scene)
                .eq(SysAiModel::getIsDefault, DEFAULT_FLAG)
                // keepId 为 null 时这个条件不参与 SQL —— 直接写 ne(id, null) 会变成
                // `id <> NULL`，SQL 里恒为 NULL，一条都更新不到
                .ne(keepId != null, SysAiModel::getId, keepId)
                .set(SysAiModel::getIsDefault, NOT_DEFAULT));
    }

    /**
     * 审计明细。
     * <p>
     * ⚠️ <b>绝不包含 apiKey 或 apiKeyCipher</b>，只放 hint 和一个「这次改了没」的布尔值。
     * 审计表是给运维和事后追查看的，不该成为密钥的第二份副本。
     */
    private Map<String, Object> auditDetail(SysAiModel model, boolean apiKeyChanged) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", model.getName());
        detail.put("scene", model.getScene());
        detail.put("provider", model.getProvider());
        detail.put("modelName", model.getModelName());
        detail.put("isDefault", model.getIsDefault() != null && model.getIsDefault() == DEFAULT_FLAG);
        detail.put("status", model.getStatus());
        detail.put("apiKeyChanged", apiKeyChanged);
        // 只记「尾部 4 位」，用来确认改成了哪一把，泄漏了也没用
        detail.put("apiKeyHint", model.getApiKeyHint());
        return detail;
    }

    /**
     * 当前生效配置 —— <b>纯转发</b>，一行计算都不做。
     * <p>
     * ⚠️ Python 不可达时<b>必须抛</b>，不能吞成空列表：
     * 空列表的意思是「确实没有任何生效配置」，而真实情况是「查不到」。
     * 这两种在管理端要给的提示完全不同（「去配一个模型」vs「先把 AI 服务起起来」），
     * 混成一种前端就只能说「暂无数据」，管理员会往错的方向查。
     * <p>
     * 这里把 50401（连不上 / 超时）统一改成 50201 并换一句更直白的话：
     * 这个接口唯一的失败原因就是 Python 没起来，前端提示「AI 服务不可用」就够了，
     * 没必要让管理员去区分「超时」和「拒绝连接」。
     */
    @Override
    public AiEffectiveConfigVO effectiveConfig() {
        AiServiceClient.EffectiveConfigData data;
        try {
            data = aiServiceClient.effectiveConfig();
        } catch (BizException e) {
            log.warn("读取 AI 生效配置失败：{}", e.getMessage());
            throw new BizException(ErrorCode.AI_SERVICE_ERROR,
                    "读不到当前生效的 AI 配置：AI 服务不可用（Python 服务可能没启动）");
        }

        AiEffectiveConfigVO vo = new AiEffectiveConfigVO();
        vo.setRefreshedAt(data.refreshedAt());
        vo.setLastError(data.lastError());
        vo.setScenes(data.scenes() == null
                ? List.of()
                : data.scenes().stream().map(AdminAiModelServiceImpl::toSceneVO).toList());
        return vo;
    }

    private static AiEffectiveConfigVO.SceneVO toSceneVO(AiServiceClient.SceneConfig source) {
        AiEffectiveConfigVO.SceneVO vo = new AiEffectiveConfigVO.SceneVO();
        vo.setScene(source.scene());
        vo.setModel(source.model());
        vo.setSource(source.source());
        vo.setBaseUrl(source.baseUrl());
        vo.setHasKey(source.hasKey());
        return vo;
    }
}
