package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.JobEventSaveDTO;
import com.zest.mindforgeai.pojo.vo.JobEventVO;
import com.zest.mindforgeai.service.JobEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;

/**
 * 用户端面试日历（求职日程）。
 * <p>
 * ★ <b>为什么权限复用 {@code app:job:*} 而不新开一组码</b>：日程是职位的子资源
 * （每条日程必挂一个职位），能看职位就该能看自己的日程。多一组码只会让
 * 角色配置多两行、而权限粒度没有任何实际变化。
 *
 * @author crx
 * @since 2026-10-16
 */
@Slf4j
@Tag(name = "用户端-面试日历")
@RestController
@RequestMapping("/app/calendar")
public class CalendarController {

    private final JobEventService jobEventService;

    public CalendarController(JobEventService jobEventService) {
        this.jobEventService = jobEventService;
    }

    @Operation(summary = "按时间范围查求职日程（不传范围则返回当前月）")
    @GetMapping("/events")
    @PreAuthorize("hasAuthority('app:job:read')")
    public Result<List<JobEventVO>> list(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return Result.success(jobEventService.listByRange(from, to));
    }

    @Operation(summary = "新建日程（笔试 / 面试 / HR 面等）")
    @PostMapping("/events")
    @PreAuthorize("hasAuthority('app:job:write')")
    public Result<JobEventVO> create(@Valid @RequestBody JobEventSaveDTO dto) {
        return Result.success(jobEventService.create(dto));
    }

    @Operation(summary = "修改日程（整体覆盖，也用于改状态）")
    @PutMapping("/events/{id}")
    @PreAuthorize("hasAuthority('app:job:write')")
    public Result<JobEventVO> update(@PathVariable Long id,
                                     @Valid @RequestBody JobEventSaveDTO dto) {
        return Result.success(jobEventService.update(id, dto));
    }

    @Operation(summary = "删除日程")
    @DeleteMapping("/events/{id}")
    @PreAuthorize("hasAuthority('app:job:write')")
    public Result<Void> delete(@PathVariable Long id) {
        jobEventService.delete(id);
        return Result.success();
    }
}
