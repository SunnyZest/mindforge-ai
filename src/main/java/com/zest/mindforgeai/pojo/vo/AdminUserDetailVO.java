package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端用户详情。
 * <p>
 * <b>为什么不直接用 {@link UserInfoVO}</b>：那个 VO 是 {@code /app/auth/me} 共用的，
 * 往里加「登录失败次数」「资源占用」这类管理端字段，等于把这些信息一并发给用户端 ——
 * 失败次数属于风控数据，不该让账号本人看到（他知道还剩几次就能卡着阈值试）。
 * <p>
 * <b>为什么不直接用 {@link UserSummaryVO}</b>：列表项是「一行一条」的轻量结构，
 * 详情要的是「这个用户到底占了多少数据、有没有被爆破」，两者字段集不同。
 * <p>
 * 字段口径：基础信息与列表一致（前端可复用同一套渲染），另外补
 * 角色明细、有效权限、登录失败计数、资源占用。
 *
 * @author crx
 * @since 2026-10-28
 */
@Schema(description = "管理端用户详情")
@Data
public class AdminUserDetailVO {

    @Schema(description = "用户 id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像地址（后端路径）")
    private String avatarUrl;

    @Schema(description = "状态：1 启用 / 0 禁用")
    private Integer status;

    /**
     * 角色 code 列表。
     * <p>
     * 刻意保留成 {@code List<String>}，与列表接口 {@link UserSummaryVO#getRoles()} 同形 ——
     * 前端现有的角色渲染代码不用改。要 roleId / 角色名请看 {@link #roleItems}。
     */
    @Schema(description = "角色 code 列表（与列表接口同形）")
    private List<String> roles;

    @Schema(description = "角色明细（含 roleId 与角色名）")
    private List<AdminUserRoleVO> roleItems;

    @Schema(description = "有效权限码列表")
    private List<String> permissions;

    @Schema(description = "最后登录时间（登录成功时写入，历史数据为 null）")
    private LocalDateTime lastLoginAt;

    /**
     * 最后登录 IP。
     * <p>
     * 只记<b>成功</b>登录；失败登录的 IP 在审计日志里（`USER_LOGIN` + `FAIL`）。
     * 历史数据为 null（该列 2026-10-28 才加上）。
     * <p>
     * ⚠️ 排障线索，不是安全凭证：开了 {@code trust-forwarded-headers} 时它的可信度取决于
     * 前面的代理是否如实覆盖写入 X-Forwarded-For。
     */
    @Schema(description = "最后登录 IP（历史数据为 null）")
    private String lastLoginIp;

    @Schema(description = "注册时间")
    private LocalDateTime createdAt;

    /**
     * 当前登录失败计数（Redis 里的实时值）。
     * <p>
     * 语义是「<b>当前锁定窗口内</b>的连续失败次数」，不是历史累计 ——
     * 登录成功会清零，key 到期（默认锁定时长）也会自动消失。
     * 想判断「是不是正在被爆破」看这个值 + {@link #loginLockRemainingSeconds}。
     */
    @Schema(description = "当前窗口内的登录失败次数（登录成功即清零）")
    private Integer failedLoginCount;

    @Schema(description = "账号剩余锁定秒数，0 表示未锁定")
    private Long loginLockRemainingSeconds;

    @Schema(description = "知识库数量")
    private Integer knowledgeBaseCount;

    @Schema(description = "文档数量")
    private Integer documentCount;

    @Schema(description = "简历数量")
    private Integer resumeCount;

    @Schema(description = "模拟面试场次")
    private Integer interviewCount;

    @Schema(description = "职位数量")
    private Integer jobCount;

    @Schema(description = "存储占用（字节）")
    private Long storageBytes;

    /**
     * 基础信息 + 角色 + 权限。资源统计与失败计数由 Service 补（它们要额外查库 / 查 Redis）。
     */
    public static AdminUserDetailVO of(User user,
                                       List<AdminUserRoleVO> roleItems,
                                       List<String> permissions) {
        AdminUserDetailVO vo = new AdminUserDetailVO();
        vo.setId(user.getId() == null ? null : String.valueOf(user.getId()));
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setStatus(user.getStatus());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setCreatedAt(user.getCreatedAt());

        List<AdminUserRoleVO> items = roleItems == null ? List.of() : roleItems;
        vo.setRoleItems(items);
        // 从明细里派生出 code 列表，保证两个字段永远不会互相矛盾
        vo.setRoles(items.stream().map(AdminUserRoleVO::getRoleCode).toList());
        vo.setPermissions(permissions == null ? List.of() : permissions);
        return vo;
    }
}
