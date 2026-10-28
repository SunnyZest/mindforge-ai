package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 面试邀约抽取结果。
 * <p>
 * ★ <b>返回的是「候选」，不是「已创建的日程」</b>。用户在前端逐条核对、
 * 改标题/时间/职位、勾选要哪几条，之后才逐条调 {@code POST /app/calendar/events}。
 * 这个接口<b>不落库</b>，也没有副作用。
 * <p>
 * ★ 为什么 {@code events} 是数组：一封邀约经常包含多轮 ——
 * 「10 月 30 日 14:00 技术一面，11 月 2 日 10:00 HR 面」。
 * 只返回一条会丢掉一半信息，而用户又不知道丢在哪。
 *
 * @author crx
 * @since 2026-10-28
 */
@Schema(description = "面试邀约抽取结果（候选，未落库）")
@Data
public class InvitationExtractVO {

    @Schema(description = "抽出的日程候选；可能为空数组（原文里没有明确的日程）")
    private List<ExtractedJobEventVO> events;

    @Schema(description = "原文有效字符数（不含空白），前端用来显示「已读取 N 字」")
    private int sourceLength;

    /**
     * 需要提醒用户注意的地方，没有则为 null。
     * <p>
     * 后端拼装，而不是让前端按字段自己判断 —— 因为「哪些字段被证据校验丢弃了」
     * 只有后端知道（前端只看到 null，分不清是「原文没写」还是「校验没过」）。
     * 多条提示用中文分号连接，前端当一行文字展示即可。
     */
    @Schema(description = "需要提醒用户注意的地方（如「图片是 OCR，可能有误差」），没有则为 null")
    private String warning;

    public static InvitationExtractVO of(List<ExtractedJobEventVO> events,
                                         int sourceLength,
                                         String warning) {
        InvitationExtractVO vo = new InvitationExtractVO();
        vo.setEvents(events);
        vo.setSourceLength(sourceLength);
        vo.setWarning(warning);
        return vo;
    }
}
