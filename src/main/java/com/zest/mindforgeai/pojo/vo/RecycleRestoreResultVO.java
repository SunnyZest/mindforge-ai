package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 恢复操作的结果。
 *
 * <p>★ <b>为什么恢复要返回这个，而不是简单的 void</b>：职位 / 面试 / 对话的关联数据
 * 靠删除前的 {@code payload_json} 快照重建。如果当初快照没采到（比如采集时抛了异常，
 * 只打了日志），恢复出来会是一个**残缺**的记录 —— 主表回来了，技能/题目/消息却没有。
 *
 * <p>那种情况下如果只返回成功，用户会以为「都回来了」，直到打开详情才发现少了一半。
 * 所以这里显式告诉前端：完整就提示成功，不完整就提示警告。
 *
 * @author crx
 * @since 2026-10-26
 */
@Data
@Accessors(chain = true)
@Schema(description = "恢复结果")
public class RecycleRestoreResultVO {

    @Schema(description = "是否完整恢复。false 表示关联数据缺失，只还原了主记录")
    private boolean complete;

    @Schema(description = "不完整时的说明；完整时为 null")
    private String warning;

    public static RecycleRestoreResultVO complete() {
        return new RecycleRestoreResultVO().setComplete(true);
    }

    public static RecycleRestoreResultVO incomplete(String warning) {
        return new RecycleRestoreResultVO().setComplete(false).setWarning(warning);
    }
}
