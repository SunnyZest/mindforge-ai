package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二次确认临时凭据响应。
 *
 * @author crx
 * @since 2026-10-11
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "二次确认临时凭证响应")
public class ConfirmTokenVO {

    @Schema(description = "临时确认凭证，使用时放入 X-Confirm-Token 请求头")
    private String confirmToken;

    @Schema(description = "有效时长（秒）")
    private long expiresInSeconds;
}
