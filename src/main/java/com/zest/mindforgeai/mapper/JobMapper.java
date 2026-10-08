package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.Job;
import org.apache.ibatis.annotations.Mapper;

/**
 * 职位 JD Mapper
 * <p>
 * 目前只用 MyBatis-Plus 自带的 CRUD，没有自定义 SQL。
 * 解析相关的技能表（{@code job_skill}）等 AI 服务接上后再加。
 *
 * @author crx
 * @since 2026-10-08
 */
@Mapper
public interface JobMapper extends BaseMapper<Job> {
}
