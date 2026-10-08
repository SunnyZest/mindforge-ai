package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeParsedVO;
import com.zest.mindforgeai.pojo.vo.ResumeVO;
import com.zest.mindforgeai.service.ResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户端简历，路径对齐文档 §十九。
 * <p>
 * 上传走 multipart/form-data（和文档上传一致）。
 * <p>
 * ⚠️ 文档里的两个接口<b>还没实现</b>，要等 Python AI 服务：
 * <ul>
 *   <li>{@code POST /resumes/{id}/parse} —— 解析简历</li>
 *   <li>{@code POST /resumes/{id}/optimize} —— AI 优化建议</li>
 * </ul>
 * 目前上传后 {@code parseStatus} 停在 {@code PENDING}，
 * {@code GET /resumes/{id}/parsed} 会如实返回这个状态。
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

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @Operation(summary = "简历列表（分页）")
    @GetMapping
    @PreAuthorize("hasAuthority('app:resume:read')")
    public Result<PageResultVO<ResumeVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword) {

        return Result.success(resumeService.pageResumes(page, pageSize, keyword));
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

    @Operation(summary = "删除简历")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:resume:write')")
    public Result<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return Result.success();
    }
}
