package com.zest.mindforgeai.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.StorageProperties;
import com.zest.mindforgeai.pojo.vo.StoredFileVO;
import com.zest.mindforgeai.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Date;

/**
 * 阿里云 OSS 实现。
 *
 * @author crx
 * @since 2026-10-05
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mindforge.storage.type", havingValue = "OSS")
public class OssFileStorageServiceImpl implements FileStorageService {

    /** 预签名 URL 默认有效期 */
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final OSS ossClient;
    private final StorageProperties props;

    public OssFileStorageServiceImpl(OSS ossClient, StorageProperties props) {
        this.ossClient = ossClient;
        this.props = props;
    }

    @Override
    public StoredFileVO upload(String objectKey, byte[] content, String contentType) {
        String bucket = props.getOss().getBucket();

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        if (StringUtils.hasText(contentType)) {
            metadata.setContentType(contentType);
        }

        try {
            ossClient.putObject(bucket, objectKey, new ByteArrayInputStream(content), metadata);
        } catch (Exception e) {
            log.error("上传 OSS 失败 objectKey={}", objectKey, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件上传失败，请稍后重试");
        }

        StoredFileVO vo = new StoredFileVO();
        vo.setObjectKey(objectKey);
        vo.setBucketName(bucket);
        vo.setStorageType(props.getType());
        vo.setSize(content.length);
        return vo;
    }

    @Override
    public String presignedUrl(String objectKey, Duration ttl) {
        Date expiration = new Date(
                System.currentTimeMillis() + (ttl == null ? DEFAULT_TTL : ttl).toMillis());
        try {
            return ossClient
                    .generatePresignedUrl(props.getOss().getBucket(), objectKey, expiration)
                    .toString();
        } catch (Exception e) {
            log.error("生成 OSS 预签名 URL 失败 objectKey={}", objectKey, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "生成下载链接失败");
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            ossClient.deleteObject(props.getOss().getBucket(), objectKey);
        } catch (Exception e) {
            // 删除失败不影响业务（文件记录已经逻辑删除），记日志由后台任务补偿
            log.warn("删除 OSS 对象失败 objectKey={}", objectKey, e);
        }
    }
}
