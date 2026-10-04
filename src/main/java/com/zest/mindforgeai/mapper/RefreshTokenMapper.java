package com.zest.mindforgeai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zest.mindforgeai.pojo.entity.SysRefreshToken;
import org.apache.ibatis.annotations.Mapper;

/**
 * 刷新令牌 Mapper
 *
 * @author crx
 * @since 2026-10-04
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<SysRefreshToken> {
}
