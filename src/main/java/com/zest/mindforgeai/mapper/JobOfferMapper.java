package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.JobOffer;
import org.apache.ibatis.annotations.Mapper;

/**
 * Offer 对比 Mapper。全部走 MyBatis-Plus 通用方法，无自定义 SQL。
 *
 * @author crx
 * @since 2026-10-14
 */
@Mapper
public interface JobOfferMapper extends BaseMapper<JobOffer> {
}
