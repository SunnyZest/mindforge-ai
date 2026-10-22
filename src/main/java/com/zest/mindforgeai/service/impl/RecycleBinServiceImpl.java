package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.RecycleResourceType;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.StorageProperties;
import com.zest.mindforgeai.mapper.RecycleBinMapper;
import com.zest.mindforgeai.pojo.entity.RecycleBin;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.RecycleBinVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.RecycleBinService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 回收站实现。
 *
 * <p>★ <b>为什么恢复 / 彻底删除用 JdbcTemplate 而不是 MyBatis-Plus</b>：
 * 项目全局开了逻辑删除（{@code logic-delete-field: deleted}），MP 会给所有
 * {@code updateById} 自动补 {@code deleted = 0} 条件 —— 而恢复要改的恰恰是一条
 * {@code deleted = 1} 的记录，用 MP 永远改不动（返回 0 行，看起来像「记录不存在」）。
 * 表名取自 {@link RecycleResourceType} 的白名单枚举，不是请求参数，没有注入风险。
 *
 * @author crx
 * @since 2026-10-22
 */
@Slf4j
@Service
public class RecycleBinServiceImpl extends ServiceImpl<RecycleBinMapper, RecycleBin>
        implements RecycleBinService {

    /** title 列宽 */
    private static final int MAX_TITLE_LEN = 255;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final StorageProperties storageProperties;

    public RecycleBinServiceImpl(JdbcTemplate jdbc,
                                 AuditService auditService,
                                 StorageProperties storageProperties) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.storageProperties = storageProperties;
    }

    // ---------- 登记 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void put(RecycleResourceType type, Long resourceId, Long userId, String title) {
        // 幂等：同一资源会「删除 → 恢复 → 再删除」。正常流程里恢复时记录已删掉，
        // 但万一上次恢复中途失败留下残留，唯一键 uk_recycle_resource 会让这次插入直接炸。
        // 先清掉再插，比在调用方做判重更省事。
        remove(new LambdaQueryWrapper<RecycleBin>()
                .eq(RecycleBin::getResourceType, type.name())
                .eq(RecycleBin::getResourceId, resourceId));

        LocalDateTime now = LocalDateTime.now();
        save(new RecycleBin()
                .setUserId(userId)
                .setResourceType(type.name())
                .setResourceId(resourceId)
                .setTitle(abbreviate(title))
                .setDeletedAt(now)
                // 恢复窗口与 OSS 保留期对齐：文件都物理删了还允许「恢复」，
                // 恢复出来只会是个空壳
                .setExpireAt(now.plusDays(retentionDays())));

        log.info("删除记录已进回收站 type={} id={} title={}", type.name(), resourceId, title);
    }

    // ---------- 列表 ----------

    @Override
    public PageResultVO<RecycleBinVO> page(long page, long pageSize, String resourceType) {
        LambdaQueryWrapper<RecycleBin> wrapper = new LambdaQueryWrapper<RecycleBin>()
                .eq(RecycleBin::getUserId, SecurityUtils.getUserId())
                // 过期的恢复不了，就别展示了（记录本身留给清理任务，见 PLAN）
                .gt(RecycleBin::getExpireAt, LocalDateTime.now())
                .orderByDesc(RecycleBin::getDeletedAt);

        RecycleResourceType type = RecycleResourceType.of(resourceType);
        if (type != null) {
            wrapper.eq(RecycleBin::getResourceType, type.name());
        }

        Page<RecycleBin> result = page(new Page<>(page, pageSize), wrapper);
        List<RecycleBinVO> records = result.getRecords().stream().map(RecycleBinVO::of).toList();
        return PageResultVO.of(result, records);
    }

    // ---------- 恢复 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long id) {
        RecycleBin bin = requireOwned(id);
        if (bin.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT, "这条记录已过恢复期，无法恢复");
        }
        RecycleResourceType type = requireType(bin.getResourceType());

        int rows = jdbc.update(
                "UPDATE " + type.getTable() + " SET deleted = 0 WHERE id = ? AND deleted = 1",
                bin.getResourceId());
        if (rows == 0) {
            // 两种可能：原行已被彻底删掉，或者已经是未删除状态。
            // 都不该继续 —— 后者继续下去会把别人的正常数据当成「恢复」成功。
            throw new BizException(ErrorCode.STATUS_CONFLICT, "原记录已恢复或已不存在，无法重复恢复");
        }

        removeById(bin.getId());

        auditService.recordSuccess(AuditConstants.ACTION_RECYCLE_RESTORE,
                AuditConstants.RESOURCE_RECYCLE, String.valueOf(bin.getResourceId()),
                Map.of("resourceType", type.name(), "title", bin.getTitle()));

        log.info("从回收站恢复 {} id={} title={}", type.name(), bin.getResourceId(), bin.getTitle());
    }

    // ---------- 彻底删除 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void purge(Long id) {
        RecycleBin bin = requireOwned(id);
        RecycleResourceType type = requireType(bin.getResourceType());

        // ⚠️ 只删 deleted = 1 的行：如果期间已经被恢复，这里必须什么都不做，
        //    否则「彻底删除」会把一条正常数据删掉 —— 这是本类最危险的一行
        jdbc.update("DELETE FROM " + type.getTable() + " WHERE id = ? AND deleted = 1",
                bin.getResourceId());

        removeById(bin.getId());

        auditService.recordSuccess(AuditConstants.ACTION_RECYCLE_PURGE,
                AuditConstants.RESOURCE_RECYCLE, String.valueOf(bin.getResourceId()),
                Map.of("resourceType", type.name(), "title", bin.getTitle()));

        log.info("彻底删除 {} id={} title={}", type.name(), bin.getResourceId(), bin.getTitle());
    }

    // ---------- 内部 ----------

    /** 越权一律 404 —— 不区分「不存在」和「不是你的」，避免探测 */
    private RecycleBin requireOwned(Long id) {
        RecycleBin bin = getById(id);
        if (bin == null || !bin.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站记录不存在");
        }
        return bin;
    }

    private RecycleResourceType requireType(String name) {
        RecycleResourceType type = RecycleResourceType.of(name);
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的资源类型：" + name);
        }
        return type;
    }

    private String abbreviate(String title) {
        if (!StringUtils.hasText(title)) {
            return "未命名";
        }
        String trimmed = title.trim();
        return trimmed.length() > MAX_TITLE_LEN ? trimmed.substring(0, MAX_TITLE_LEN) : trimmed;
    }

    private int retentionDays() {
        int days = storageProperties.getRetentionDays();
        return days > 0 ? days : 30;
    }
}
