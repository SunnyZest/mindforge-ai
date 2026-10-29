package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.ResumeOptimizeDTO;
import com.zest.mindforgeai.pojo.dto.ResumeUpdateDTO;
import com.zest.mindforgeai.pojo.vo.AtsScoreVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeOptimizeVO;
import com.zest.mindforgeai.pojo.vo.ResumeParsedVO;
import com.zest.mindforgeai.pojo.vo.ResumeVO;
import com.zest.mindforgeai.service.AtsScoreService;
import com.zest.mindforgeai.service.ResumePdfService;
import com.zest.mindforgeai.service.ResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 用户端简历，路径对齐文档 §十九。
 * <p>
 * 上传走 multipart/form-data（和文档上传一致）。
 * <p>
 * ⚠️ 两个 AI 接口都是<b>同步慢接口</b>（几十秒到几分钟）：
 * {@code /{id}/parse} 和 {@code /{id}/optimize}。前端要么把超时放宽直接等，
 * 要么调用后轮询 {@code GET /{id}/parsed} 看状态。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "用户端-简历")
@RestController
@RequestMapping("/app/resumes")
public class ResumeController {

    private final ResumeService resumeService;
    private final AtsScoreService atsScoreService;
    private final ResumePdfService resumePdfService;

    public ResumeController(ResumeService resumeService, AtsScoreService atsScoreService,
                            ResumePdfService resumePdfService) {
        this.resumeService = resumeService;
        this.atsScoreService = atsScoreService;
        this.resumePdfService = resumePdfService;
    }

    @Operation(summary = "简历列表（分页）")
    @GetMapping
    @PreAuthorize("hasAuthority('app:resume:read')")
    public Result<PageResultVO<ResumeVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(resumeService.pageResumes(page, pageSize, keyword, sortBy, sortOrder));
    }

    @Operation(summary = "简历详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('app:resume:read')")
    public Result<ResumeDetailVO> detail(@PathVariable Long id) {
        return Result.success(resumeService.getDetail(id));
    }

    /**
     * 上传简历。
     * <p>
     * 用 multipart 而不是 JSON —— 简历本身是个文件。
     * {@code name} 不传就取原文件名。
     */
    @Operation(summary = "上传简历")
    @PostMapping
    @PreAuthorize("hasAuthority('app:resume:write')")
    public Result<ResumeVO> upload(@RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String name,
                                   @RequestParam(required = false) String targetPosition) {
        return Result.success(resumeService.upload(file, name, targetPosition));
    }

    /**
     * 取解析结果。
     * <p>
     * 单独开一个接口而不是复用详情：前端要轮询「解析好了没」，
     * 这个响应体只有解析相关字段，比详情小得多。
     */
    @Operation(summary = "获取简历解析结果")
    @GetMapping("/{id}/parsed")
    @PreAuthorize("hasAuthority('app:resume:read')")
    public Result<ResumeParsedVO> parsed(@PathVariable Long id) {
        return Result.success(resumeService.getParsed(id));
    }

    /**
     * 触发解析。
     * <p>
     * ⚠️ <b>同步接口，一次可能几十秒</b>（下载 + 提取文本 + 模型生成）。
     * 前端两种用法都行：把超时放宽直接等，或者调用后轮询
     * {@code GET /resumes/{id}/parsed} —— 解析期间状态是 {@code PARSING}。
     * <p>
     * 解析失败时这个接口会返回错误（含原因），同时简历的
     * {@code parseStatus} 变成 {@code FAILED}、{@code errorMessage} 记下原因，
     * 详情和 {@code /parsed} 里都能看到。
     */
    @Operation(summary = "解析简历")
    @PostMapping("/{id}/parse")
    @PreAuthorize("hasAuthority('app:resume:parse')")
    public Result<ResumeParsedVO> parse(@PathVariable Long id) {
        return Result.success(resumeService.parse(id));
    }

    /**
     * AI 优化建议。
     * <p>
     * ⚠️ <b>只给建议，不改简历</b> —— 文档明确要求「AI 优化只返回建议，
     * 不直接覆盖原始简历」。所以这个接口没有副作用，可以反复调。
     * <p>
     * 前置条件：简历<b>已解析过</b>。没解析的话原文还在 PDF 里，AI 读不到。
     * <p>
     * ⚠️ <b>慢接口，实测要几分钟</b>（模型要写 8 条建议）。前端别用默认超时。
     * <p>
     * ★ 返回的 {@code items} 分两类，<b>必须分开展示</b>：
     * {@code needsFact = false} 是改写建议（{@code rewritten} 可用）；
     * {@code needsFact = true} 是「这里缺事实」（{@code rewritten} 为 null，
     * 看 {@code missingFact}）。混在一起用户会以为 AI 已经替他把经历写好了。
     *
     * @param dto 可空 —— 两个字段都可选，不传 body 就是「整体分析、不针对岗位」
     */
    @Operation(summary = "简历 AI 优化建议")
    @PostMapping("/{id}/optimize")
    @PreAuthorize("hasAuthority('app:resume:optimize')")
    public Result<ResumeOptimizeVO> optimize(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) ResumeOptimizeDTO dto) {
        return Result.success(resumeService.optimize(id, dto));
    }

    @Operation(summary = "在线精修或采纳 AI 建议后更新简历内容")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('app:resume:write')")
    public Result<ResumeDetailVO> update(@PathVariable Long id,
                                         @Valid @RequestBody ResumeUpdateDTO dto) {
        return Result.success(resumeService.update(id, dto));
    }

    /**
     * ATS 关键词跑分。
     * <p>
     * ⚠️ 和 {@code POST /resume-job-analysis}（匹配分析）是<b>两回事</b>：
     * 这个只算「JD 里的关键词在简历里出现了没有」，纯本地计算、毫秒级返回、不烧 token；
     * 匹配分析会调模型给出五类结论（几十秒）。前端两个入口要分开，别混成一句话说。
     * <p>
     * 权限用 {@code app:resume:read} 而不是 {@code app:resume:match} ——
     * 它不消耗模型额度，没必要多要一个权限。
     */
    @Operation(summary = "ATS 关键词跑分（纯本地计算，毫秒级）",
            description = "返回关键词覆盖率得分 + 逐词命中情况 + 缺失的必须项；不调模型、不落库")
    @GetMapping("/{id}/ats-score")
    @PreAuthorize("hasAuthority('app:resume:read')")
    public Result<AtsScoreVO> atsScore(@PathVariable Long id,
                                       @RequestParam Long jobId) {
        return Result.success(atsScoreService.score(id, jobId));
    }

    @Operation(summary = "删除简历")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:resume:write')")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }

    /**
     * 导出简历 PDF（服务端渲染）。
     * <p>
     * 返回的是<b>二进制流</b>，不是 {@code Result} 信封 —— 这是全项目唯一一个
     * 不走统一响应体的接口。前端因此不能用 {@code http.get()}（它会拆信封、
     * 对 blob 响应抛 {@code code: undefined}），要用 {@code http.raw} 配
     * {@code responseType: 'blob'}，并且错误分支也得自己解析 blob 里的 JSON。
     * <p>
     * ⚠️ 前置条件：简历<b>已解析过</b>。没解析时没有结构化数据，
     * 拿原文硬渲出来是一坨纯文本，那正是这次改造要解决的问题。
     * <p>
     * 并发有上限（4），超出返回 42901。导出是 CPU 密集操作，
     * 不限制的话几个人同时点就能拖慢整个服务。
     */
    @Operation(summary = "导出简历 PDF（服务端渲染）",
            description = "返回 application/pdf 二进制流；简历未解析时 40001，并发打满 42901")
    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('app:resume:read')")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        ResumePdfService.PdfFile pdf = resumePdfService.render(id);

        // 手写而不是用 ContentDisposition.builder()：
        // builder 的 .filename(ascii) 会被随后的 .filename(utf8, UTF_8) 覆盖掉，
        // 最后只剩一个 RFC 2047 编码词（=?UTF-8?Q?...?=）——那是邮件头的写法，
        // 浏览器虽然认，但不是我们想要的「ASCII 兜底 + RFC 5987」两段式。
        String disposition = "attachment; filename=\"resume.pdf\"; filename*=UTF-8''"
                + UriUtils.encodePathSegment(pdf.fileName(), StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                // 简历是个人隐私，不让浏览器和中间代理缓存
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(pdf.content().length)
                .body(pdf.content());
    }
}
