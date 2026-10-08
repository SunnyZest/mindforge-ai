package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.SysAiModelMapper;
import com.zest.mindforgeai.pojo.dto.AiModelCreateDTO;
import com.zest.mindforgeai.pojo.dto.AiModelUpdateDTO;
import com.zest.mindforgeai.pojo.entity.SysAiModel;
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

    public AdminAiModelServiceImpl(SysAiModelMapper aiModelMapper,
                                   AesCipher aesCipher,
                                   AuditService auditService) {
        this.aiModelMapper = aiModelMapper;
        this.aesCipher = aesCipher;
        this.auditService = auditService;
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

        aiModelMapper.insert(model);

        // 同一个用途下只能有一个默认。插完再清，保证无论哪一步失败都不会出现「两个默认」
        if (asDefault) {
            clearOtherDefaults(model.getScene(), model.getId());
        }

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

        aiModelMapper.update(null, wrapper);

        SysAiModel updated = aiModelMapper.selectById(id);

        // 改成默认时，清掉同一个用途下的其他默认。
        // 用「更新后的 scene」而不是入参 —— 这次可能同时改了用途。
        if (dto.getIsDefault() != null && Boolean.TRUE.equals(dto.getIsDefault())) {
            clearOtherDefaults(updated.getScene(), id);
        }

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
     * 「同一用途只有一个默认」是业务规则，但<b>没有用唯一索引去约束</b> ——
     * MySQL 的唯一索引对「多行 is_default=0」不友好（0 会互相冲突），
     * 而条件唯一索引本项目没用过。所以在代码里保证。
     */
    private void clearOtherDefaults(String scene, Long keepId) {
        aiModelMapper.update(null, new LambdaUpdateWrapper<SysAiModel>()
                .eq(SysAiModel::getScene, scene)
                .eq(SysAiModel::getIsDefault, DEFAULT_FLAG)
                .ne(SysAiModel::getId, keepId)
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
}
