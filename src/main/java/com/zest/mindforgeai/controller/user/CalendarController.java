package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.InvitationExtractDTO;
import com.zest.mindforgeai.pojo.dto.JobEventSaveDTO;
import com.zest.mindforgeai.pojo.vo.InvitationExtractVO;
import com.zest.mindforgeai.pojo.vo.JobEventVO;
import com.zest.mindforgeai.service.InvitationExtractService;
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
    private final InvitationExtractService invitationExtractService;

    public CalendarController(JobEventService jobEventService,
                              InvitationExtractService invitationExtractService) {
        this.jobEventService = jobEventService;
        this.invitationExtractService = invitationExtractService;
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

    /**
     * 从 HR 面试邀约原文里抽取日程候选。
     * <p>
     * ★ <b>不落库、无副作用</b>，返回的是「候选」。用户在前端逐条核对、改完、勾选，
     * 之后才逐条调 {@code POST /app/calendar/events} 真正创建。
     * 这样「AI 猜的」和「用户认的」是两件事，AI 猜错了不会变成脏数据。
     * <p>
     * ★ <b>为什么权限用 {@code app:job:parse} 而不是 {@code app:job:write}</b>：
     * 这个接口不改任何数据，它消耗的是 AI 额度，性质与「解析 JD」「解析简历」相同。
     * 用 {@code write} 会让「能改日程但不能用 AI」的角色也能刷模型。
     * <p>
     * ⚠️ <b>同步接口，预期 10~20 秒，最坏 120 秒</b>。前端超时设的 120 秒，
     * 后端调用 AI 的读超时设 115 秒 —— 后端要先于前端返回业务错误，
     * 否则用户看到的是自己浏览器报的「请求超时」，而不知道到底怎么了。
     * <p>
     * ⚠️ 每用户每小时限 20 次（见 {@code InvitationExtractServiceImpl#checkQuota}）。
     */
    @Operation(summary = "从面试邀约原文抽取日程（不落库，返回候选）")
    @PostMapping("/invitations/extract")
    @PreAuthorize("hasAuthority('app:job:parse')")
    public Result<InvitationExtractVO> extractInvitation(
            @Valid @RequestBody InvitationExtractDTO dto) {
        return Result.success(invitationExtractService.extract(dto));
    }
}
