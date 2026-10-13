package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.InterviewAnswerDTO;
import com.zest.mindforgeai.pojo.dto.InterviewCreateDTO;
import com.zest.mindforgeai.pojo.dto.InterviewPracticeDTO;
import com.zest.mindforgeai.pojo.vo.InterviewAnswerResultVO;
import com.zest.mindforgeai.pojo.vo.InterviewDetailVO;
import com.zest.mindforgeai.pojo.vo.InterviewMistakeStatsVO;
import com.zest.mindforgeai.pojo.vo.InterviewMistakeVO;
import com.zest.mindforgeai.pojo.vo.InterviewReportVO;
import com.zest.mindforgeai.pojo.vo.InterviewVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端 AI 面试，路径对齐文档 §二十二。
 * <p>
 * 流程是四步，<b>慢的只有三步</b>（都调模型，几十秒到几分钟）：
 * <pre>
 * POST   /app/interviews                创建       快
 * POST   /app/interviews/{id}/generate  出题       慢  ← 幂等，可直接重试
 * POST   /app/interviews/{id}/start     开始作答   快
 * POST   /app/interviews/{id}/answers   提交单题   慢
 * POST   /app/interviews/{id}/finish    结束+报告  慢
 * GET    /app/interviews/{id}/report    取报告     快
 * </pre>
 * ⚠️ 三个慢接口都是<b>同步</b>的，前端要么把超时放宽直接等，要么把状态展示成
 * 「正在准备/评分中」。没有做异步任务 + 轮询 —— 交互上用户本来就在等一个结果，
 * 做成轮询只是把等待藏起来，还多一套任务状态要维护。
 *
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Tag(name = "用户端-AI 面试")
@RestController
@RequestMapping("/app/interviews")
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @Operation(summary = "面试列表（分页）")
    @GetMapping
    @PreAuthorize("hasAuthority('app:interview:read')")
    public Result<PageResultVO<InterviewVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(interviewService.pageInterviews(
                page, pageSize, keyword, status, sortBy, sortOrder));
    }

    @Operation(summary = "面试错题本统计概览")
    @GetMapping("/mistakes/stats")
    @PreAuthorize("hasAuthority('app:interview:read')")
    public Result<InterviewMistakeStatsVO> mistakeStats() {
        return Result.success(interviewService.getMistakeStats());
    }

    @Operation(summary = "面试错题与薄弱点分页列表")
    @GetMapping("/mistakes")
    @PreAuthorize("hasAuthority('app:interview:read')")
    public Result<PageResultVO<InterviewMistakeVO>> pageMistakes(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(defaultValue = "WEAK") String masteryStatus,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String keyword) {
        return Result.success(interviewService.pageMistakes(
                page, pageSize, masteryStatus, questionType, difficulty, keyword));
    }

    @Operation(summary = "错题本单题重练刷分")
    @PostMapping("/questions/{questionId}/practice")
    @PreAuthorize("hasAuthority('app:interview:answer')")
    public Result<InterviewMistakeVO> practiceQuestion(
            @PathVariable Long questionId,
            @Valid @RequestBody InterviewPracticeDTO dto) {
        return Result.success(interviewService.practiceQuestion(questionId, dto));
    }

    /**
     * 面试详情（含题目列表）。
     * <p>
     * ⚠️ 每道题的<b>参考答案要点只在面试结束后返回</b>（{@code status = FINISHED}）——
     * 进行中给了等于把答案提前泄露出去。
     */
    @Operation(summary = "面试详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('app:interview:read')")
    public Result<InterviewDetailVO> detail(@PathVariable Long id) {
        return Result.success(interviewService.getDetail(id));
    }

    /**
     * 创建面试（快）。
     * <p>
     * ⚠️ <b>只建一行数据，不出题</b> —— 出题是下一步（{@code /generate}）。
     * 拆开是为了让「参数错」和「AI 失败」分得清，也让出题失败后能单独重试。
     */
    @Operation(summary = "创建面试")
    @PostMapping
    @PreAuthorize("hasAuthority('app:interview:write')")
    public Result<InterviewVO> create(@Valid @RequestBody InterviewCreateDTO dto) {
        return Result.success(interviewService.create(dto));
    }

    /**
     * 出题（慢）。
     * <p>
     * ★ <b>幂等</b>：已有题目会先清后出，所以出题失败、或者想换一套题，
     * 直接再调一次就行，不用删了重建。
     * <p>
     * ⚠️ 面试<b>已开始作答后不能再出题</b>（会把答过的题换掉）。
     */
    @Operation(summary = "生成面试题")
    @PostMapping("/{id}/generate")
    @PreAuthorize("hasAuthority('app:interview:write')")
    public Result<InterviewDetailVO> generate(@PathVariable Long id) {
        return Result.success(interviewService.generate(id));
    }

    /** 开始作答（快）。幂等 —— 重复调用不会报错，返回的依然是详情 */
    @Operation(summary = "开始面试")
    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('app:interview:start')")
    public Result<InterviewDetailVO> start(@PathVariable Long id) {
        return Result.success(interviewService.start(id));
    }

    /**
     * 提交单题回答（慢）。
     * <p>
     * ⚠️ {@code answer} <b>允许为空</b> —— 跳过一道题是正常行为，会给低分而不是报错。
     * <p>
     * 返回里同时带上「本题分析」和「下一道未作答的题」，
     * 前端提交后不用再发一次请求拿下一题。
     */
    @Operation(summary = "提交回答")
    @PostMapping("/{id}/answers")
    @PreAuthorize("hasAuthority('app:interview:answer')")
    public Result<InterviewAnswerResultVO> answer(@PathVariable Long id,
                                                 @Valid @RequestBody InterviewAnswerDTO dto) {
        return Result.success(interviewService.answer(id, dto));
    }

    /**
     * 结束面试并生成复盘报告（慢）。
     * <p>
     * 一道题都没答时会拒绝 —— 没有素材可复盘，硬让模型写只会得到一篇套话。
     * 重复调用会重新生成并覆盖上一次的报告。
     */
    @Operation(summary = "结束面试并生成报告")
    @PostMapping("/{id}/finish")
    @PreAuthorize("hasAuthority('app:interview:report')")
    public Result<InterviewReportVO> finish(@PathVariable Long id) {
        return Result.success(interviewService.finish(id));
    }

    /** 取复盘报告。还没结束时返回 404（而不是一个字段全空的报告） */
    @Operation(summary = "获取面试报告")
    @GetMapping("/{id}/report")
    @PreAuthorize("hasAuthority('app:interview:report')")
    public Result<InterviewReportVO> report(@PathVariable Long id) {
        return Result.success(interviewService.getReport(id));
    }

    @Operation(summary = "删除面试")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:interview:write')")
    public Result<Void> delete(@PathVariable Long id) {
        interviewService.delete(id);
        return Result.success();
    }
}
