package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.UploadConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.pojo.entity.SysFile;
import com.zest.mindforgeai.pojo.dto.ChangePasswordDTO;
import com.zest.mindforgeai.pojo.dto.UserProfileUpdateDTO;
import com.zest.mindforgeai.pojo.entity.User;
import com.zest.mindforgeai.pojo.vo.UserProfileVO;
import com.zest.mindforgeai.pojo.vo.UserPublicVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.FileService;
import com.zest.mindforgeai.service.RefreshTokenService;
import com.zest.mindforgeai.service.UserProfileService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 用户自己的资料服务实现
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Service
public class UserProfileServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserProfileService {

    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final FileService fileService;

    public UserProfileServiceImpl(PasswordEncoder passwordEncoder,
                                  RefreshTokenService refreshTokenService,
                                  AuditService auditService,
                                  FileService fileService) {
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.fileService = fileService;
    }

    @Override
    public UserProfileVO getProfile() {
        return UserProfileVO.of(requireCurrentUser());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO updateProfile(UserProfileUpdateDTO dto) {
        User user = requireCurrentUser();
        Long userId = user.getId();

        // 两个字段都没传就没什么可改的，不必白打一次 UPDATE
        if (dto.getNickname() == null && dto.getAvatarUrl() == null) {
            return UserProfileVO.of(user);
        }

        // MyBatis-Plus 默认策略：null 字段不进 UPDATE 语句 → 天然支持局部更新
        updateById(new User()
                .setId(userId)
                .setNickname(dto.getNickname())
                .setAvatarUrl(dto.getAvatarUrl())
                .setUpdatedAt(LocalDateTime.now()));

        log.info("用户修改自己的资料 userId={} 昵称={} 头像={}",
                userId, dto.getNickname() != null, dto.getAvatarUrl() != null);

        // 只记「改了哪几项」，不记具体值 —— 昵称可能包含个人信息，审计表不该变成个人资料副本
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nicknameChanged", dto.getNickname() != null);
        detail.put("avatarChanged", dto.getAvatarUrl() != null);
        auditService.recordSuccess(AuditConstants.ACTION_USER_UPDATE_PROFILE,
                AuditConstants.RESOURCE_USER, String.valueOf(userId), detail);

        return UserProfileVO.of(getById(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO uploadAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择要上传的图片");
        }
        // 先按字节数拦一道 —— 让 10MB 的文件在进 OSS 之前就被挡掉
        if (file.getSize() > UploadConstants.MAX_AVATAR_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "头像不能超过 " + (UploadConstants.MAX_AVATAR_BYTES / 1024 / 1024) + "MB");
        }
        String ext = UploadConstants.extensionOf(file.getOriginalFilename());
        if (!UploadConstants.AVATAR_EXT.contains(ext)) {
            // ⚠️ 只放图片。头像的读取接口是【公开】的（<img src> 带不上 Authorization），
            //    口子越小越好 —— 放 PDF 进去等于开了一个公开的文件分享通道。
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "头像只支持 " + String.join(" / ", new TreeSet<>(UploadConstants.AVATAR_EXT)));
        }

        Long userId = SecurityUtils.getUserId();
        SysFile stored = fileService.upload(file, FileService.BIZ_AVATAR);

        // ★ 存的是【后端路径】而不是 OSS 预签名 URL ——
        //   预签名会过期，存进库里过几天头像就全裂了；这里每次请求现签一个。
        String avatarUrl = UploadConstants.AVATAR_URL_PREFIX + stored.getId();
        updateById(new User()
                .setId(userId)
                .setAvatarUrl(avatarUrl)
                .setUpdatedAt(LocalDateTime.now()));

        log.info("用户上传头像 userId={} fileId={} size={}", userId, stored.getId(), stored.getFileSize());

        // 只记「传了多大、什么类型」，不记 URL —— 审计表不该变成文件清单
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fileId", String.valueOf(stored.getId()));
        detail.put("fileSize", stored.getFileSize());
        detail.put("contentType", stored.getContentType());
        auditService.recordSuccess(AuditConstants.ACTION_USER_UPLOAD_AVATAR,
                AuditConstants.RESOURCE_USER, String.valueOf(userId), detail);

        return UserProfileVO.of(getById(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordDTO dto) {
        User user = requireCurrentUser();
        Long userId = user.getId();

        // ① 校验原密码。
        //    只认 accessToken 就允许改密码的话，token 一旦泄漏（XSS / 日志 / 截图），
        //    攻击者拿到就能直接接管账号 —— 所以必须再验一次只有本人知道的东西。
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPasswordHash())) {
            // 失败要留痕：拿着有效 token 却不知道原密码，是很可疑的信号
            auditService.recordFailure(AuditConstants.ACTION_USER_CHANGE_PASSWORD,
                    AuditConstants.RESOURCE_USER, String.valueOf(userId),
                    Map.of("reason", "原密码不正确"));
            throw new BizException(ErrorCode.PARAM_INVALID, "原密码不正确");
        }

        // ② 新旧不能相同 —— 否则「改了密码」是个假动作，
        //    用户以为旧密码已经失效，实际上还能登。
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }

        updateById(new User()
                .setId(userId)
                .setPasswordHash(passwordEncoder.encode(dto.getNewPassword()))
                .setUpdatedAt(LocalDateTime.now()));

        // ③ 撤销该用户全部 refresh token —— 密码换了，之前发出去的所有会话都该失效。
        //    常见场景：怀疑账号被盗，改密码是为了把别人踢下线；
        //    如果不动 refresh token，攻击者手里的 token 还能一直换新 access token，
        //    「改密码」就白改了。
        //    ⚠️ access token 是无状态的，剩余有效期内仍可用（最长 30 分钟），
        //    这是 JWT 的固有取舍，和登出是同一个窗口。
        refreshTokenService.revokeAll(userId);

        log.info("用户修改密码成功 userId={}", userId);
        auditService.recordSuccess(AuditConstants.ACTION_USER_CHANGE_PASSWORD,
                AuditConstants.RESOURCE_USER, String.valueOf(userId),
                Map.of("note", "已撤销该用户全部 refreshToken"));
    }

    @Override
    public UserPublicVO getPublicProfile(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return UserPublicVO.of(user);
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 取当前登录用户。
     * <p>
     * 目标 id 一律来自 SecurityContext —— 所有「操作自己」的方法都走这里，
     * 从根上避免「入参传个 id 就改到别人」。
     */
    private User requireCurrentUser() {
        Long userId = SecurityUtils.getUserId();
        User user = getById(userId);
        if (user == null) {
            // 正常走不到：JwtAuthenticationFilter 已经确认过用户存在。
            // 只有「token 有效但账号刚被删掉」这个极窄的窗口会命中。
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }
}
