package com.zest.mindforgeai.pojo.dto;

import com.zest.mindforgeai.pojo.entity.InterviewQuestion;
import com.zest.mindforgeai.pojo.entity.InterviewReport;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 模拟面试回收站的 payload 快照。
 *
 * <p>★ 为什么需要快照：删面试时 {@code interview_question} 与 {@code interview_report}
 * 都是**物理删**（两张表都没有 deleted 列）。只把 {@code interview.deleted} 改回 0
 * 恢复出来会是一个「没有题目、没有报告」的空面试 —— 而这两样恰恰是花了 AI token
 * 生成出来的，丢了最可惜。
 *
 * <p>报告用 List 而不是单个：同一个面试重新生成过就会有历史报告，
 * 只存一份会把其余的悄悄丢掉。
 *
 * @author crx
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "面试回收站快照")
public class InterviewRecyclePayload {

    @Schema(description = "题目（删除时被物理删掉的行）")
    private List<InterviewQuestion> questions;

    @Schema(description = "报告（可能不止一份，重新生成过就有历史）")
    private List<InterviewReport> reports;
}
