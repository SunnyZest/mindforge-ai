package com.zest.mindforgeai.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 从邀约原文抽出来的一条日程 —— <b>没有落库</b>，等用户确认。
 * <p>
 * ★ <b>所有字段都可能是 null</b>，null 的正确语义是「原文里没写」，
 * 不是「AI 偷懒」。前端必须逐项兜底，不能假设抽出来就是对的。
 * <p>
 * ★ <b>为什么 {@code matchedJobId} 和 {@code eventAt} 是提交的硬前提</b>：
 * 后端 {@code job_event} 这两列都是 NOT NULL。缺任何一项，
 * 前端就不该允许这一行被勾选提交 —— 让用户先补，而不是让后端报一个
 * 「关联职位不能为空」然后整批失败。
 * <p>
 * 字段与前端 {@code packages/shared/src/types.ts} 的 {@code ExtractedJobEventVO} 一一对应，
 * 改这里必须同步改那边（这是跨端契约，不是内部结构）。
 *
 * @author crx
 * @since 2026-10-28
 */
@Schema(description = "从邀约原文抽出的一条日程（未落库）")
@Data
public class ExtractedJobEventVO {

    @Schema(description = "日程标题；原文没写标题时可能为 null（前端会拿公司+岗位兜底）")
    private String title;

    @Schema(description = "公司名称")
    private String companyName;

    @Schema(description = "岗位名称")
    private String positionName;

    @Schema(description = "轮次 → 日程类型：WRITTEN_TEST / INTERVIEW_1 / INTERVIEW_2 / HR / OFFER_TALK / OTHER；"
            + "原文没写轮次时为 null，<b>不要</b>补默认值")
    private String eventType;

    @Schema(description = "开始时间；null = 原文没有可用的时间")
    private LocalDateTime eventAt;

    /**
     * 时间是否由相对表述换算而来（「下周三下午 2 点」「明天」）。
     * <p>
     * ★ 这是<b>唯一允许推导</b>的字段，且必须显式标出来让人核对 ——
     * 换算错一小时，邮件提醒就会在错的时间发出去，而用户毫不知情。
     * 原文写死了日期（「2026 年 10 月 30 日 14:00」）时必须是 false。
     */
    @Schema(description = "时间是否由「明天」「下周三」这类相对表述换算而来，需要用户核对")
    private boolean eventAtDerived;

    @Schema(description = "预计时长（分钟），0~1440；原文没写为 null")
    private Integer durationMinutes;

    @Schema(description = "形式：ONSITE 现场 / ONLINE 线上 / PHONE 电话；原文没写为 null")
    private String eventMode;

    @Schema(description = "地点（现场面试的地址）")
    private String location;

    @Schema(description = "线上会议链接（腾讯会议 / Zoom / Teams）")
    private String meetingUrl;

    @Schema(description = "面试官")
    private String interviewer;

    @Schema(description = "备注")
    private String note;

    /**
     * 字段名 → 支撑该值的原文片段。缺 key = 该字段没抽到。
     * <p>
     * ★ 后端对每个字段都做了 {@code 原文.contains(evidence)} 校验，
     * <b>校验不过的字段已经被丢弃</b>（值变成 null，key 也不会出现在这里）。
     * 所以前端拿到 null 时不用怀疑「是不是校验没过」，就是原文里没写。
     */
    @Schema(description = "字段名 → 支撑该值的原文片段；校验不通过的值已被丢弃")
    private Map<String, String> evidence;

    @Schema(description = "匹配到的已有职位 id；null = 没匹配上，必须让用户自己选")
    private String matchedJobId;

    @Schema(description = "匹配到的已有职位标题")
    private String matchedJobTitle;
}
