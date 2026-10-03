package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper
 *
 * @author crx
 * @since 2026-10-03
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<SysAuditLog> {
}
