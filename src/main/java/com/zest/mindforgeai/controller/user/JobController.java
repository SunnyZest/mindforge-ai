package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.JobCreateDTO;
import com.zest.mindforgeai.pojo.dto.JobUpdateDTO;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.JobVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端职位 JD，路径对齐文档 §二十。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Tag(name = "用户端-职位 JD")
@RestController
@RequestMapping("/app/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @Operation(summary = "职位列表（分页）")
    @GetMapping
    @PreAuthorize("hasAuthority('app:job:read')")
    public Result<PageResultVO<JobVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword) {

        return Result.success(jobService.pageJobs(page, pageSize, keyword));
    }

    @Operation(summary = "职位详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('app:job:read')")
    public Result<JobDetailVO> detail(@PathVariable Long id) {
        return Result.success(jobService.getDetail(id));
    }

    @Operation(summary = "创建职位 JD")
    @PostMapping
    @PreAuthorize("hasAuthority('app:job:write')")
    public Result<JobVO> create(@Valid @RequestBody JobCreateDTO dto) {
        return Result.success(jobService.create(dto));
    }

    @Operation(summary = "修改职位 JD")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('app:job:write')")
    public Result<JobVO> update(@PathVariable Long id, @Valid @RequestBody JobUpdateDTO dto) {
        return Result.success(jobService.update(id, dto));
    }

    @Operation(summary = "删除职位 JD")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:job:write')")
    public Result<Void> delete(@PathVariable Long id) {
        jobService.delete(id);
        return Result.success();
    }

    /**
     * 触发解析：技能 / 经验 / 学历 / 职责。
     * <p>
     * ⚠️ <b>同步接口，要等模型生成</b>。解析期间 {@code parseStatus} 是 {@code PARSING}，
     * 前端可以轮询 {@code GET /jobs/{id}} 看状态。
     * <p>
     * 失败时这个接口返回错误（含原因），同时职位的 {@code parseStatus} 变成
     * {@code FAILED}、{@code errorMessage} 记下原因。
     */
    @Operation(summary = "解析职位 JD")
    @PostMapping("/{id}/parse")
    @PreAuthorize("hasAuthority('app:job:parse')")
    public Result<JobDetailVO> parse(@PathVariable Long id) {
        return Result.success(jobService.parse(id));
    }
}
