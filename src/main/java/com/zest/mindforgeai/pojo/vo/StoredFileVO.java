package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件写入对象存储后的结果。
 * <p>
 * 这是存储层的返回值，用来组装 {@code sys_file} 记录，不直接返回给前端。
 *
 * @author crx
 * @since 2026-10-05
 */
@Schema(description = "存储结果")
@Data
public class StoredFileVO {

    @Schema(description = "对象在存储中的 key")
    private String objectKey;

    @Schema(description = "桶名")
    private String bucketName;

    @Schema(description = "存储类型：OSS / MINIO / LOCAL")
    private String storageType;

    @Schema(description = "文件字节数")
    private long size;
}
