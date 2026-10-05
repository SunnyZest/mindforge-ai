package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件元数据 Mapper
 *
 * @author crx
 * @since 2026-10-05
 */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {

    /**
     * 查出「已逻辑删除且超过恢复窗口」的记录，供后台清理任务使用。
     * <p>
     * <b>故意绕过 MyBatis-Plus 的逻辑删除</b> —— MP 会给所有它生成的 SQL 加
     * {@code deleted = 0}，而这里要查的恰恰是 {@code deleted = 1} 的记录，
     * 所以只能写自定义 SQL。
     * <p>
     * SQL 见 {@code resources/mapper/SysFileMapper.xml}。
     *
     * @param cutoff 截止时间，早于它的才算过期
     * @param limit  单批条数，避免一次处理太多
     */
    List<SysFile> selectPurgeable(@Param("cutoff") LocalDateTime cutoff,
                                  @Param("limit") int limit);

    /**
     * 物理删除记录（绕过 MP 的逻辑删除），清理任务用。
     */
    int physicalDeleteById(@Param("id") Long id);
}
