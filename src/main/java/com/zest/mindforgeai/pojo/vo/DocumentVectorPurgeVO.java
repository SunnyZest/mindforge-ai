package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 待清理向量的文档 —— 返回给 Python AI 服务。
 * <p>
 * 文档被删除后，数据库记录是逻辑删除，但向量库里的向量还在。
 * AI 服务拿到这个列表后调向量库的 delete，再回写「已清理」。
 *
 * @author crx
 * @since 2026-10-07
 */
@Schema(description = "待清理向量的文档（内部接口）")
@Data
public class DocumentVectorPurgeVO {

    @Schema(description = "文档 id —— AI 服务用它去向量库删")
    private String documentId;

    @Schema(description = "所属知识库 id（仅用于日志排查）")
    private String knowledgeBaseId;

    @Schema(description = "归属用户 id（仅用于日志排查）")
    private String userId;

    @Schema(description = "原始文件名（仅用于日志排查）")
    private String fileName;
}
