package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理后台首页统计，文档 §二十三。
 * <p>
 * 注意：本项目不做多租户，所以<b>没有 tenantCount</b>（文档里也特意注明了这点）。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "管理后台首页统计")
@Data
public class DashboardVO {

    @Schema(description = "用户数（不含已删除）")
    private Long userCount;

    @Schema(description = "知识库数（不含已删除）")
    private Long knowledgeBaseCount;

    @Schema(description = "文档数（不含已删除）")
    private Long documentCount;

    @Schema(description = "AI 调用次数")
    private Long aiRequestCount;

    @Schema(description = "消耗 token 总数")
    private Long tokenUsage;
}
