package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.ResumeMatchDTO;
import com.zest.mindforgeai.pojo.vo.ResumeMatchVO;
import com.zest.mindforgeai.service.ResumeJobAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端简历-JD 匹配，路径对齐文档 §二十一。
 * <p>
 * 两个端点是一对：<b>POST 触发分析（慢，调模型）</b>、
 * <b>GET 取上次结果（快，不调模型）</b>。
 * 分开是为了「进页面先看上次结果」—— 不然用户每刷新一次就要重跑一次模型。
 *
 * @author crx
 * @since 2026-10-10
 */
@Slf4j
@Tag(name = "用户端-简历匹配")
@RestController
@RequestMapping("/app/resume-job-analysis")
public class ResumeJobAnalysisController {

    private final ResumeJobAnalysisService analysisService;

    public ResumeJobAnalysisController(ResumeJobAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * 触发匹配分析。
     * <p>
     * ⚠️ <b>慢接口，实测 100~200 秒</b>（要逐条判断 JD 的每一项要求）。
     * 前端超时至少给 5 分钟，页面上要有明确的「正在分析」反馈。
     * <p>
     * 前置条件：简历和职位<b>都已解析过</b> —— 匹配的输入就是这两段原文。
     * <p>
     * ★ <b>返回结果的重点是 {@code items} 里的五类结论</b>，不是 {@code overallScore}。
     * 页面上请把五类分开展示（分组的清单或 tab），别混成一个大列表 ——
     * 用户要分清「哪些改改简历就行（表达缺口）、哪些真得去学（真实缺口）」，
     * 这正是这个功能唯一的价值。{@code overallScore} 只当辅助信息放。
     * <p>
     * ⚠️ 同一对（简历 + 职位）只保留一条结果，重跑会覆盖。
     */
    @Operation(summary = "触发简历与职位匹配分析")
    @PostMapping
    @PreAuthorize("hasAuthority('app:resume:match')")
    public Result<ResumeMatchVO> analyze(@Valid @RequestBody ResumeMatchDTO dto) {
        return Result.success(analysisService.analyze(dto));
    }

    /**
     * 取最近一次分析结果（快）。
     * <p>
     * 没做过匹配时返回 {@code 40413}（而不是一个空对象）——
     * 前端据此显示「还没分析过，点按钮开始」。
     */
    @Operation(summary = "取最近一次匹配分析结果")
    @GetMapping
    @PreAuthorize("hasAuthority('app:resume:read')")
    public Result<ResumeMatchVO> latest(@RequestParam Long resumeId,
                                        @RequestParam Long jobId) {
        return Result.success(analysisService.getLatest(resumeId, jobId));
    }
}
