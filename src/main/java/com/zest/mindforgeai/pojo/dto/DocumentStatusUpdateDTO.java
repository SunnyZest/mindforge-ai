package com.zest.mindforgeai.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 回写文档处理状态 —— Python AI 服务调用。
 * <p>
 * <b>字段语义：null 表示「不修改这个字段」</b>，所以 Python 可以只报变化的那个阶段，
 * 比如刚解析完就只传 {@code parseStatus}。
 *
 * @author crx
 * @since 2026-10-06
 */
@Schema(description = "回写文档处理状态")
@Data
public class DocumentStatusUpdateDTO {

    @Schema(description = "解析状态：PENDING / PARSING / SUCCESS / FAILED；null 表示不改")
    private String parseStatus;

    @Schema(description = "分块状态：PENDING / CHUNKING / SUCCESS / FAILED；null 表示不改")
    private String chunkStatus;

    @Schema(description = "向量化状态：PENDING / EMBEDDING / SUCCESS / FAILED；null 表示不改")
    private String vectorStatus;

    /**
     * 失败原因。
     * <p>
     * 这个字段和上面三个不一样：<b>只要不是 null 就会写入</b>（包括空字符串）。
     * 因为「清空上次的错误信息」也是一个明确动作，用 null 表达「不改」的话就没法清空了。
     */
    @Schema(description = "失败原因；传空字符串表示清空，传 null 表示不改")
    private String errorMessage;

    @Schema(description = "页数；null 表示不改")
    private Integer pageCount;
}
