package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管理端：一个<b>变体</b>（含它的全部历史版本）。
 *
 * <p>列表接口按变体分组返回，而不是把几百行版本平铺 —— 平铺的话前端还得自己
 * 按 (scene, name) 再聚一次，而且「这个变体现在生效的是哪一版」看不出来。
 *
 * @author crx
 * @since 2026-10-10
 */
@Data
@Schema(description = "提示词变体（含版本历史）")
public class PromptGroupVO {

    @Schema(description = "场景")
    private String scene;

    @Schema(description = "变体名")
    private String name;

    @Schema(description = "给用户看的选项名")
    private String label;

    @Schema(description = "是否允许用户切换")
    private Boolean userSelectable;

    @Schema(description = "是否该场景的默认")
    private Boolean isDefault;

    @Schema(description = "当前已发布版本的 id；一个都没发布时为 null")
    private String publishedId;

    @Schema(description = "最新版本号（含草稿）")
    private Integer latestVersion;

    @Schema(description = "版本列表，按版本号倒序")
    private List<PromptVO> versions;
}
