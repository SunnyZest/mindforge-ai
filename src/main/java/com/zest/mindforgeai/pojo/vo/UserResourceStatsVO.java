package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单个用户的资源占用统计，管理端用户详情专用。
 * <p>
 * 为什么单独一个 VO 而不是塞进 {@link AdminUserDetailVO} 直接查：
 * 这几个数字来自 5 张不同的表，必须<b>一次 SQL 查完</b>（见 {@code UserMapper.xml}
 * 的 {@code selectResourceStats}）。分 5 次 count 就是 5 次往返，
 * 而管理端详情页一次只查一个人，完全没必要省这点 SQL 复杂度，
 * 但也没必要多 4 次网络往返。
 * <p>
 * 计数口径统一为 {@code deleted = 0}（回收站里的不算占用）。
 *
 * @author crx
 * @since 2026-10-28
 */
@Schema(description = "用户资源占用统计")
@Data
public class UserResourceStatsVO {

    @Schema(description = "知识库数量")
    private Integer knowledgeBaseCount;

    @Schema(description = "文档数量")
    private Integer documentCount;

    @Schema(description = "简历数量")
    private Integer resumeCount;

    @Schema(description = "模拟面试场次")
    private Integer interviewCount;

    @Schema(description = "职位数量")
    private Integer jobCount;

    @Schema(description = "存储占用（字节，sys_file 求和）")
    private Long storageBytes;
}
