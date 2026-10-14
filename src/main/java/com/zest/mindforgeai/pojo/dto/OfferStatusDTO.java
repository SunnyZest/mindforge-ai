package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 变更 Offer 状态（接受 / 拒绝 / 回到考虑中）。
 *
 * @author crx
 * @since 2026-10-14
 */
@Schema(description = "变更 Offer 状态")
@Data
public class OfferStatusDTO {

    @Schema(description = "状态：PENDING 考虑中 / ACCEPTED 已接受 / DECLINED 已拒绝",
            example = "ACCEPTED")
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "PENDING|ACCEPTED|DECLINED", message = "状态只能是 PENDING / ACCEPTED / DECLINED")
    private String status;
}
