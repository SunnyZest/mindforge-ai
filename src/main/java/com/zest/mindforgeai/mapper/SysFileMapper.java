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

    /**
     * 查出「还不能删」的文件 id —— 回收站里还有未过期记录的。
     *
     * <p>为什么需要：恢复窗口和 OSS 保留期都是 30 天，但两者会错位。第 29 天恢复一份文档，
     * 第 30 天清理任务就可能把它的 OSS 对象删掉，结果恢复出来的文档下载不了。
     *
     * <p>只覆盖三种会占用文件的资源：文档、简历、知识库（级联到它下面的文档）。
     * 其余资源（Offer / 日程 / 职位 / 面试 / 对话）不持有 sys_file。
     */
    List<Long> findProtectedFileIds();

    /** 给受保护的文件续期，避免它反复占满清理批次 */
    int touchDeletedAt(@Param("id") Long id);
}
