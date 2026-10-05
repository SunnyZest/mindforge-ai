package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.SysFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务（业务层）。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>{@link FileStorageService} —— 只管「怎么存」（OSS / MinIO / 本地）</li>
 *   <li>本接口 —— 管「存哪儿、叫什么名、要不要复用、记什么账」（业务规则）</li>
 * </ul>
 * 所以 objectKey 的命名规则在这里，不在存储层。
 *
 * @author crx
 * @since 2026-10-05
 */
public interface FileService extends IService<SysFile> {

    /** 业务类型：知识库文档 */
    String BIZ_DOCUMENT = "documents";

    /** 业务类型：简历 */
    String BIZ_RESUME = "resumes";

    /** 业务类型：头像 */
    String BIZ_AVATAR = "avatars";

    /**
     * 上传文件：写入对象存储 + 落 {@code sys_file} 记录。
     * <p>
     * 含<b>秒传</b>：同一用户上传内容相同的文件时，复用已存在的对象，不重复占用存储。
     *
     * @param file    上传的文件
     * @param bizType 业务类型，决定 objectKey 的前缀（用上面的 BIZ_* 常量）
     */
    SysFile upload(MultipartFile file, String bizType);

    /** 按 id 取文件并校验归属（不存在 → 40402，不是自己的 → 40302） */
    SysFile getOwned(Long fileId);

    /** 生成预签名下载 URL */
    String downloadUrl(SysFile file);

    /**
     * 逻辑删除文件记录（写 {@code deleted = 1} + {@code deleted_at = now}）。
     * <p>
     * <b>不动 OSS 对象</b> —— 留出恢复窗口，到期由 {@code FilePurgeTask} 判断引用计数后清理。
     * <p>
     * ⚠️ 不能用 MP 的 {@code removeById}：它只改 {@code deleted}，不会写 {@code deleted_at}，
     * 而清理任务正是靠 {@code deleted_at} 判断是否超过恢复窗口的。
     */
    void softDelete(SysFile file);
}
