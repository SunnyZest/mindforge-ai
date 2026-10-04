package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 「用户 → 角色 code」的一行结果，仅用于批量查询后在代码里分组，
 * 不直接返回给前端。
 *
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "用户角色对应项")
@Data
public class UserRoleItemVO {

    private Long userId;

    private String roleCode;
}
