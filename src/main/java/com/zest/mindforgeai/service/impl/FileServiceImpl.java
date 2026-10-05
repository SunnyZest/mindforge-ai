package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.SysFileMapper;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.pojo.vo.StoredFileVO;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.service.FileStorageService;
import com.zest.mindforgeai.utils.HashUtils;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件服务实现
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Service
public class FileServiceImpl extends ServiceImpl<SysFileMapper, SysFile> implements FileService {

    /** 预签名下载 URL 有效期 */
    private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final FileStorageService storageService;

    public FileServiceImpl(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysFile upload(MultipartFile file, String bizType) {
        Long userId = SecurityUtils.getUserId();

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取上传文件失败");
        }

        String hash = HashUtils.sha256Hex(content);
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "unnamed";

        // 秒传：同一用户传过相同内容 → 复用已有对象，不再上传一遍。
        // 注意是「复用对象 + 新建记录」：不同业务（文档/简历）可能引用同一份内容，
        // 各自需要自己的 sys_file 记录。
        SysFile existing = getOne(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getUserId, userId)
                .eq(SysFile::getContentHash, hash)
                .last("LIMIT 1"));

        if (existing != null) {
            log.info("秒传命中 userId={} hash={} 复用 objectKey={}",
                    userId, hash, existing.getObjectKey());
            return saveRecord(userId, originalName, existing.getObjectKey(),
                    existing.getBucketName(), existing.getStorageType(),
                    file.getContentType(), content.length, hash);
        }

        String objectKey = buildObjectKey(bizType, userId, originalName);
        StoredFileVO stored = storageService.upload(objectKey, content, file.getContentType());

        log.info("文件上传成功 userId={} objectKey={} size={}", userId, objectKey, stored.getSize());

        return saveRecord(userId, originalName, stored.getObjectKey(), stored.getBucketName(),
                stored.getStorageType(), file.getContentType(), stored.getSize(), hash);
    }

    @Override
    public SysFile getOwned(Long fileId) {
        SysFile file = getById(fileId);
        if (file == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        if (!file.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN);
        }
        return file;
    }

    @Override
    public String downloadUrl(SysFile file) {
        return storageService.presignedUrl(file.getObjectKey(), DOWNLOAD_TTL);
    }

    @Override
    public void softDelete(SysFile file) {
        // 手动写 deleted + deleted_at。
        // MP 的 removeById 只会设 deleted，deleted_at 会一直是 NULL，
        // 那样清理任务就永远认为它「没到期」，对象永远清不掉。
        update(new LambdaUpdateWrapper<SysFile>()
                .eq(SysFile::getId, file.getId())
                .set(SysFile::getDeleted, 1)
                .set(SysFile::getDeletedAt, LocalDateTime.now()));
    }

    /* ---------- 内部工具 ---------- */

    /**
     * objectKey 命名规则：{@code {业务}/{userId}/{yyyy}/{MM}/{uuid}.{ext}}
     * <ul>
     *   <li><b>按 userId 隔离</b> —— 用户删号时能一把清理，也避免互相覆盖</li>
     *   <li><b>文件名用 UUID</b> —— 用户可能传同名文件，用原名会互相覆盖；
     *       原名存在 {@code sys_file.original_name} 里，不影响展示</li>
     *   <li><b>按年月分片</b> —— 控制台里好找，也方便将来配生命周期规则</li>
     * </ul>
     */
    private String buildObjectKey(String bizType, Long userId, String originalName) {
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0 && dot < originalName.length() - 1) {
            ext = originalName.substring(dot).toLowerCase();
        }
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return String.format("%s/%d/%s/%s%s",
                bizType, userId, LocalDate.now().format(DATE_PATH), uuid, ext);
    }

    private SysFile saveRecord(Long userId, String originalName, String objectKey, String bucketName,
                               String storageType, String contentType, long size, String hash) {
        SysFile record = new SysFile()
                .setUserId(userId)
                .setOriginalName(originalName)
                .setObjectKey(objectKey)
                .setBucketName(bucketName)
                .setStorageType(storageType)
                .setContentType(contentType)
                .setFileSize(size)
                .setContentHash(hash);
        save(record);
        return record;
    }
}
