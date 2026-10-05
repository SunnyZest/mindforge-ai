package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.StoredFileVO;

import java.time.Duration;

/**
 * 对象存储抽象。
 * <p>
 * 业务代码只依赖这个接口，具体后端（OSS / MinIO / 本地磁盘）由
 * {@code mindforge.storage.type} 决定 —— 换存储不用改业务逻辑。
 * <p>
 * <b>为什么要有这层抽象</b>：文件存哪儿是基础设施决策，不该渗透到文档、简历等业务里。
 *
 * @author crx
 * @since 2026-10-05
 */
public interface FileStorageService {

    /**
     * 写入一个对象。
     *
     * @param objectKey   对象 key（调用方按约定生成，见 {@code FileService}）
     * @param content     文件内容
     * @param contentType MIME 类型，可空
     * @return 存储结果（含 objectKey / bucket / 大小 / SHA-256）
     */
    StoredFileVO upload(String objectKey, byte[] content, String contentType);

    /**
     * 生成<b>预签名下载 URL</b>。
     * <p>
     * 客户端拿这个临时 URL 直连存储下载，<b>文件流量不经过后端</b>。
     * 这也是用对象存储而不是本地磁盘的主要理由之一。
     *
     * @param objectKey 对象 key
     * @param ttl       有效期
     */
    String presignedUrl(String objectKey, Duration ttl);

    /**
     * 删除对象。
     * <p>
     * 注意：业务上我们优先用 {@code sys_file.deleted} 逻辑删除，
     * 这个方法留给后台清理任务用。
     */
    void delete(String objectKey);
}
