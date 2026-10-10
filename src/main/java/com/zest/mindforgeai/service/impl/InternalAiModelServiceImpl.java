package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zest.mindforgeai.mapper.SysAiModelMapper;
import com.zest.mindforgeai.pojo.entity.SysAiModel;
import com.zest.mindforgeai.pojo.vo.AiModelRuntimeVO;
import com.zest.mindforgeai.service.InternalAiModelService;
import com.zest.mindforgeai.utils.AesCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Service
public class InternalAiModelServiceImpl implements InternalAiModelService {

    private static final int ENABLED = 1;

    private final SysAiModelMapper aiModelMapper;
    private final AesCipher aesCipher;

    public InternalAiModelServiceImpl(SysAiModelMapper aiModelMapper, AesCipher aesCipher) {
        this.aiModelMapper = aiModelMapper;
        this.aesCipher = aesCipher;
    }

    @Override
    public List<AiModelRuntimeVO> listEnabled() {
        // 默认的排前面：Python 侧同一个 scene 有多条时取第一条，所以顺序就是取舍依据
        List<SysAiModel> models = aiModelMapper.selectList(new LambdaQueryWrapper<SysAiModel>()
                .eq(SysAiModel::getStatus, ENABLED)
                .orderByDesc(SysAiModel::getIsDefault)
                .orderByAsc(SysAiModel::getId));

        List<AiModelRuntimeVO> result = new ArrayList<>(models.size());
        for (SysAiModel model : models) {
            AiModelRuntimeVO runtime = toRuntime(model);
            if (runtime != null) {
                result.add(runtime);
            }
        }

        // ⚠️ 只记「下发了哪几条」，绝不记 Key —— 这个响应体里带明文 Key
        log.info("向 AI 服务下发模型配置 {} 条：{}", result.size(),
                result.stream().map(m -> m.scene() + "/" + m.modelName()).toList());
        return result;
    }

    /**
     * 转成运行时视图。
     *
     * @return 解密失败时返回 {@code null}（跳过这条）——
     *         宁可让 Python 整条回退到环境变量，也不要下发一个半截的配置
     *         （比如 model 是新的、Key 是旧的），那种问题极难排查
     */
    private AiModelRuntimeVO toRuntime(SysAiModel model) {
        String apiKey = null;
        if (StringUtils.hasText(model.getApiKeyCipher())) {
            try {
                apiKey = aesCipher.decrypt(model.getApiKeyCipher());
            } catch (RuntimeException e) {
                log.error("解密 API Key 失败，跳过这条配置 id={} scene={} provider={} —— "
                                + "多半是 mindforge.ai-config.secret-key 换过，"
                                + "需要在管理端重新录入 Key",
                        model.getId(), model.getScene(), model.getProvider(), e);
                return null;
            }
        }

        return new AiModelRuntimeVO(
                String.valueOf(model.getId()),
                model.getScene(),
                model.getProvider(),
                model.getModelName(),
                model.getBaseUrl(),
                apiKey,
                model.getTemperature(),
                model.getMaxTokens(),
                model.getTimeoutSeconds(),
                Integer.valueOf(1).equals(model.getIsDefault()));
    }
}
