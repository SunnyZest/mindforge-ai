package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.AiUsageReportDTO;

/**
 * 接收 Python 上报的 AI 使用量。
 *
 * <p>⚠️ 语义是 <b>fire-and-forget</b>：调用方（Python）不关心这条记录有没有落库，
 * 所以这里【不抛异常】—— 记账失败只打 WARNING。一次成功的 AI 调用
 * 不能因为「记不了账」而被当成失败。
 *
 * @author crx
 * @since 2026-10-10
 */
public interface InternalAiUsageService {

    /** 写一条用量记录。任何失败都在内部消化，不往外抛。 */
    void record(AiUsageReportDTO dto);
}
