package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.mapper.InterviewQuestionMapper;
import com.zest.mindforgeai.mapper.InterviewReportMapper;
import com.zest.mindforgeai.pojo.dto.InterviewRecyclePayload;
import com.zest.mindforgeai.pojo.entity.InterviewQuestion;
import com.zest.mindforgeai.pojo.entity.InterviewReport;
import com.zest.mindforgeai.service.RecycleRestoreHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模拟面试的回收站恢复策略。
 *
 * <p>删面试时：{@code interview} 逻辑删（恢复改回 0 即可，由 RecycleBinServiceImpl 处理），
 * 而 {@code interview_question} / {@code interview_report} 是**物理删** —— 必须快照重建。
 *
 * <p>★ 这两张表的数据是 AI 生成的（出题 + 评分报告），丢了就得重新烧一遍 token，
 * 所以恢复的完整度比其它资源更重要。
 *
 * @author crx
 * @since 2026-10-26
 */
@Slf4j
@Component
public class InterviewRecycleHandler implements RecycleRestoreHandler {

    private final InterviewQuestionMapper questionMapper;
    private final InterviewReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    public InterviewRecycleHandler(InterviewQuestionMapper questionMapper,
                                   InterviewReportMapper reportMapper,
                                   ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.reportMapper = reportMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public RecycleResourceType type() {
        return RecycleResourceType.INTERVIEW;
    }

    @Override
    public String capturePayload(Long interviewId) {
        InterviewRecyclePayload payload = new InterviewRecyclePayload(
                questionMapper.selectList(new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getInterviewId, interviewId)),
                reportMapper.selectList(new LambdaQueryWrapper<InterviewReport>()
                        .eq(InterviewReport::getInterviewId, interviewId)));

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 不阻断删除：宁可恢复出来缺题缺报告，也不要让用户删不掉
            log.error("采集面试回收站快照失败 interviewId={}，恢复后题目与报告会缺失", interviewId, e);
            return null;
        }
    }

    @Override
    public boolean restoreRelated(Long interviewId, String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            log.warn("面试回收站记录没有 payload，只能恢复主表 interviewId={}（题目与报告会缺失）", interviewId);
            return false;
        }

        InterviewRecyclePayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, InterviewRecyclePayload.class);
        } catch (JsonProcessingException e) {
            log.error("解析面试回收站快照失败 interviewId={}，只恢复主表", interviewId, e);
            return false;
        }

        // ⚠️ 重建时清掉原 id：这些行是物理删后重新插入的，用新雪花更安全
        List<InterviewQuestion> questions = payload.getQuestions();
        if (questions != null) {
            for (InterviewQuestion q : questions) {
                q.setId(null);
                questionMapper.insert(q);
            }
        }
        List<InterviewReport> reports = payload.getReports();
        if (reports != null) {
            for (InterviewReport r : reports) {
                r.setId(null);
                reportMapper.insert(r);
            }
        }

        log.info("面试关联数据已重建 interviewId={} 题目={} 报告={}",
                interviewId,
                questions == null ? 0 : questions.size(),
                reports == null ? 0 : reports.size());
        return true;
    }
}
