package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.pojo.entity.RecycleBin;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 回收站列表项。
 *
 * <p>⚠️ {@code id} 与 {@code resourceId} 都是 <b>String</b> —— 雪花 ID 有 19 位，
 * 前端 JS 的 Number 只能精确到 2^53，直接给数字会被悄悄截断成另一个 id。
 *
 * @author crx
 * @since 2026-10-22
 */
@Data
@Schema(description = "回收站记录")
public class RecycleBinVO {

    @Schema(description = "回收站记录 id，恢复与彻底删除都用它")
    private String id;

    @Schema(description = "资源类型，如 JOB / OFFER")
    private String resourceType;

    @Schema(description = "资源类型中文名")
    private String resourceTypeName;

    @Schema(description = "原资源 id")
    private String resourceId;

    @Schema(description = "展示名（职位名 / 公司名 / 对话标题…）")
    private String title;

    @Schema(description = "删除时间")
    private LocalDateTime deletedAt;

    @Schema(description = "到期时间，过了就不能再恢复")
    private LocalDateTime expireAt;

    @Schema(description = "剩余可恢复天数，0 表示今天到期")
    private long remainingDays;

    public static RecycleBinVO of(RecycleBin bin) {
        RecycleBinVO vo = new RecycleBinVO();
        vo.setId(String.valueOf(bin.getId()));
        vo.setResourceType(bin.getResourceType());

        RecycleResourceType type = RecycleResourceType.of(bin.getResourceType());
        vo.setResourceTypeName(type == null ? bin.getResourceType() : type.getLabel());

        vo.setResourceId(String.valueOf(bin.getResourceId()));
        vo.setTitle(bin.getTitle());
        vo.setDeletedAt(bin.getDeletedAt());
        vo.setExpireAt(bin.getExpireAt());
        vo.setRemainingDays(remainingDays(bin.getExpireAt()));
        return vo;
    }

    /** 不足一天按 0 算 —— 负数会让前端显示成「还剩 -3 天」 */
    private static long remainingDays(LocalDateTime expireAt) {
        if (expireAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(LocalDateTime.now(), expireAt).toDays());
    }
}
