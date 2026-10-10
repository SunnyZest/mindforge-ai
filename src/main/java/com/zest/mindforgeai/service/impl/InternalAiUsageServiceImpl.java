package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.mapper.AiUsageRecordMapper;
import com.zest.mindforgeai.pojo.dto.AiUsageReportDTO;
import com.zest.mindforgeai.pojo.entity.AiUsageRecord;
import com.zest.mindforgeai.service.InternalAiUsageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Service
public class InternalAiUsageServiceImpl implements InternalAiUsageService {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";

    /** 和建表语句里的列宽保持一致，超了先截断 —— 否则 INSERT 直接报错、整条记录丢掉。 */
    private static final int MAX_REQUEST_ID = 64;
    private static final int MAX_SCENE = 64;
    private static final int MAX_PROVIDER = 64;
    private static final int MAX_MODEL_NAME = 128;
    private static final int MAX_STATUS = 32;
    private static final int MAX_ERROR = 1000;

    private final AiUsageRecordMapper usageMapper;

    public InternalAiUsageServiceImpl(AiUsageRecordMapper usageMapper) {
        this.usageMapper = usageMapper;
    }

    @Override
    public void record(AiUsageReportDTO dto) {
        try {
            int prompt = nz(dto.promptTokens());
            int completion = nz(dto.completionTokens());
            // total 以调用方给的为准；没给就自己加（embedding 只有 prompt，也走这条）
            int total = dto.totalTokens() != null ? dto.totalTokens() : prompt + completion;

            usageMapper.insert(new AiUsageRecord()
                    .setUserId(parseUserId(dto.userId()))
                    .setRequestId(truncate(dto.requestId(), MAX_REQUEST_ID))
                    .setScene(truncate(dto.scene(), MAX_SCENE))
                    .setProvider(truncate(dto.provider(), MAX_PROVIDER))
                    .setModelName(truncate(dto.modelName(), MAX_MODEL_NAME))
                    .setPromptTokens(prompt)
                    .setCompletionTokens(completion)
                    .setTotalTokens(total)
                    .setDurationMs(dto.durationMs())
                    // 只认 SUCCESS，其余一律归成 FAILED —— 免得写入各种拼写变体，
                    // 统计时按 status 分组就散了
                    .setStatus(SUCCESS.equals(dto.status()) ? SUCCESS : FAILED)
                    .setErrorMessage(truncate(dto.errorMessage(), MAX_ERROR))
                    .setCreatedAt(LocalDateTime.now()));
        } catch (RuntimeException e) {
            // ⚠️ 这里【必须吞掉】。对 Python 来说这是 fire-and-forget 的上报，
            //    它不关心落库结果；但如果我们往外抛，Python 那边会看到一次 500，
            //    日志里就会出现「上报失败」的噪音，而真正的 AI 调用其实成功了。
            //    记账失败只影响统计口径，不影响任何业务。
            log.warn("写入 AI 使用量失败 scene={} model={} —— 只影响统计，不影响业务",
                    dto.scene(), dto.modelName(), e);
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static Long parseUserId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            // 系统自动调用时可能是空，或者传了非数字 —— 记 null，不要因此丢掉整条记录
            log.debug("AI 使用量里的 userId 不是数字，按空处理：{}", raw);
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
