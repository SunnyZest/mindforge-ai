package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.KnowledgeBase;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库项
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "知识库项")
@Data
public class KnowledgeBaseVO {

    @Schema(description = "知识库 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "状态：1 启用 / 0 禁用")
    private Integer status;

    @Schema(description = "文档数量")
    private Integer documentCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static KnowledgeBaseVO of(KnowledgeBase kb) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(kb.getId() == null ? null : String.valueOf(kb.getId()));
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setStatus(kb.getStatus());
        vo.setDocumentCount(kb.getDocumentCount());
        vo.setCreatedAt(kb.getCreatedAt());
        vo.setUpdatedAt(kb.getUpdatedAt());
        return vo;
    }
}
