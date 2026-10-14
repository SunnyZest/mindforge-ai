package com.zest.mindforgeai.controller.user;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.OfferCompareDTO;
import com.zest.mindforgeai.pojo.dto.OfferCreateDTO;
import com.zest.mindforgeai.pojo.dto.OfferStatusDTO;
import com.zest.mindforgeai.pojo.dto.OfferUpdateDTO;
import com.zest.mindforgeai.pojo.vo.OfferCompareVO;
import com.zest.mindforgeai.pojo.vo.OfferVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.OfferService;
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
 * 用户端 Offer 对比（五维打分表）。
 * <p>
 * 五个维度：薪资包 / 技术栈发展 / 平台背书 / 加班强度 / 通勤距离，各 1-10 分由用户自己打。
 * 全部是本地计算，<b>不消耗模型额度</b>。
 *
 * @author crx
 * @since 2026-10-14
 */
@Slf4j
@Tag(name = "用户端-Offer 对比")
@RestController
@RequestMapping("/app/offers")
public class OfferController {

    private final OfferService offerService;

    public OfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    @Operation(summary = "Offer 列表（分页，可按状态筛选）")
    @GetMapping
    @PreAuthorize("hasAuthority('app:offer:read')")
    public Result<PageResultVO<OfferVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {

        return Result.success(offerService.pageOffers(page, pageSize, keyword, status, sortBy, sortOrder));
    }

    @Operation(summary = "Offer 详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('app:offer:read')")
    public Result<OfferVO> detail(@PathVariable Long id) {
        return Result.success(offerService.getDetail(id));
    }

    @Operation(summary = "新增 Offer", description = "可带 jobId 从投递看板导入（自动带出公司名与岗位名，薪资需自己填数字）")
    @PostMapping
    @PreAuthorize("hasAuthority('app:offer:write')")
    public Result<OfferVO> create(@Valid @RequestBody OfferCreateDTO dto) {
        return Result.success(offerService.create(dto));
    }

    @Operation(summary = "修改 Offer（只更新传了的字段）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('app:offer:write')")
    public Result<OfferVO> update(@PathVariable Long id,
                                  @Valid @RequestBody OfferUpdateDTO dto) {
        return Result.success(offerService.update(id, dto));
    }

    @Operation(summary = "变更 Offer 状态（接受 / 拒绝 / 回到考虑中）")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('app:offer:write')")
    public Result<OfferVO> updateStatus(@PathVariable Long id,
                                        @Valid @RequestBody OfferStatusDTO dto) {
        return Result.success(offerService.updateStatus(id, dto));
    }

    @Operation(summary = "删除 Offer")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:offer:write')")
    public Result<Void> delete(@PathVariable Long id) {
        offerService.delete(id);
        return Result.success();
    }

    /**
     * 多 Offer 对比。
     * <p>
     * ⚠️ 用 POST 而不是 GET：要传 id 列表和五个权重，塞进 query 又长又难读。
     * 但它<b>不改任何数据</b>，所以权限按读操作算（{@code app:offer:read}）。
     * <p>
     * 权重不落库、每次由请求带 —— 用户拖一下滑块就能看排名怎么变，
     * 落库反而要设计「改权重」的接口和并发问题（见 {@code OfferCompareDTO} 的说明）。
     */
    @Operation(summary = "多 Offer 对比（2~5 个，可带权重）",
            description = "返回加权总分排名、各维度最优、一句话决策建议；权重不传 = 五维等权")
    @PostMapping("/compare")
    @PreAuthorize("hasAuthority('app:offer:read')")
    public Result<OfferCompareVO> compare(@Valid @RequestBody OfferCompareDTO dto) {
        return Result.success(offerService.compare(dto));
    }
}
