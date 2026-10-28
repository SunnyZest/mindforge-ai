package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端文档列表项。
 * <p>
 * 与用户端的 {@link DocumentVO} 的差别：多了<b>归属人</b>和<b>所属知识库名称</b>。
 * 管理员是按「谁上传的 / 在哪个库里」来找文档的，用户端不需要（自己的文档自己清楚）。
 *
 * @author crx
 * @since 2026-10-08
 */
@Schema(description = "管理端文档列表项")
@Data
public class AdminDocumentVO {

    @Schema(description = "文档 id（字符串，避免 JS 精度丢失）")
    private String documentId;

    @Schema(description = "所属知识库 id")
    private String knowledgeBaseId;

    @Schema(description = "所属知识库名称")
    private String knowledgeBaseName;

    @Schema(description = "上传者用户 id")
    private String userId;

    @Schema(description = "上传者用户名")
    private String username;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件类型（扩展名大写）")
    private String fileType;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    /**
     * 对应的 {@code sys_file} 记录 id，没有关联文件（如网页导入）时为 null。
     * <p>
     * 给管理员排查用：拿这个 id 可以去文件表 / 回收站里对照，
     * 判断「文档还在但对象没了」这类问题。
     */
    @Schema(description = "文件记录 id（sys_file），网页导入的文档为 null")
    private String fileId;

    @Schema(description = "聚合后的总状态，见 DocumentStatusUtils")
    private String status;

    @Schema(description = "解析状态")
    private String parseStatus;

    @Schema(description = "分块状态")
    private String chunkStatus;

    @Schema(description = "向量化状态")
    private String vectorStatus;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "页数")
    private Integer pageCount;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间（状态回写会刷新它）")
    private LocalDateTime updatedAt;

    /* ---------- 以下字段只在详情接口里填，列表里为 null ---------- */

    /**
     * 分块数（{@code document_chunk} 的行数）。
     * <p>
     * 只在详情里查：列表页每行都 count 一次子表，20 条就是 20 次额外查询，
     * 而列表并不需要这个数字。
     */
    @Schema(description = "分块数（仅详情）")
    private Integer chunkCount;

    /**
     * 预签名下载地址（有效期 1 小时）。
     * <p>
     * 只有文档关联了 {@code sys_file} 时才生成；生成失败（如存储未配置）时为 null，
     * 不影响详情其余字段返回 —— 管理员排障时更需要「文档在不在」，
     * 而不是因为拿不到下载链接就整页 500。
     */
    @Schema(description = "预签名下载地址（仅详情，1 小时有效）")
    private String downloadUrl;

    /**
     * 从上传到处理完成的墙钟耗时（毫秒），只在总状态为 {@code COMPLETED} 时给出。
     * <p>
     * ⚠️ 这是个<b>近似值</b>：库里没有单独的「开始处理时间」和「解析耗时」列，
     * 只能拿 {@code created_at → updated_at} 的差值。它包含了排队等待的时间，
     * 也不等于解析器的纯耗时。想看精确耗时需要在流水线里打点，那是另一件事。
     * <p>
     * 未完成 / 失败时返回 null —— 半截的耗时会误导人。
     */
    @Schema(description = "上传到完成的耗时（毫秒，仅 COMPLETED 时有值，含排队时间）")
    private Long processDurationMs;
}
