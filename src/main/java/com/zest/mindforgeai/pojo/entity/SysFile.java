package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件对象存储元数据
 *
 * @author crx
 * @since 2026-10-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_file")
@Schema(description = "SysFile对象,文件对象存储元数据表")
public class SysFile implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "上传者用户 id，逻辑关联 user.id")
    private Long userId;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "对象存储中的 key")
    private String objectKey;

    @Schema(description = "桶名")
    private String bucketName;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "文件字节数")
    private Long fileSize;

    @Schema(description = "内容 SHA-256，用于秒传与去重")
    private String contentHash;

    /**
     * 业务类型：documents 文档 / resumes 简历 / avatars 头像。
     * <p>
     * ⚠️ 不能靠 object_key 的前缀判断业务 —— 秒传是按 (userId, contentHash) 去重的，
     * <b>不区分业务</b>：同一份字节先当简历传、再当头像是，头像记录的 objectKey
     * 会指向 {@code resumes/…}。所以让记录自己记住「我属于哪个业务」。
     */
    @Schema(description = "业务类型：documents / resumes / avatars")
    private String bizType;

    @Schema(description = "存储类型：OSS / MINIO / LOCAL")
    private String storageType;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "逻辑删除：0 未删除 / 1 已删除")
    private Integer deleted;

    /**
     * 逻辑删除时间；未删除为 NULL。
     * <p>
     * 后台清理任务（{@code FilePurgeTask}）靠它判断是否超过恢复窗口 ——
     * 只有删除超过 {@code mindforge.storage.retention-days} 天的记录，
     * 才会去物理删除 OSS 对象。
     * <p>
     * ⚠️ MyBatis-Plus 的逻辑删除只会改 {@code deleted}，不会写这个字段，
     * 所以删除时必须手动设置（见 {@code FileServiceImpl#softDelete}）。
     */
    @Schema(description = "逻辑删除时间")
    private LocalDateTime deletedAt;
}
