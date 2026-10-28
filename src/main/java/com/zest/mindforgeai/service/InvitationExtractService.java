package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.InvitationExtractDTO;
import com.zest.mindforgeai.pojo.vo.InvitationExtractVO;

/**
 * 面试邀约抽取。
 * <p>
 * 解决的是「日程数据怎么进来」的问题：写 {@code job_event} 的入口原本只有前端日历页
 * 一个手工表单，用户得自己敲标题、时间、轮次。而 HR 发来的邀约原文里这些信息全都有，
 * 只是散在自然语言里。
 * <p>
 * ★ <b>这个服务只产出「候选」，不落库</b>。落库仍然走
 * {@code POST /app/calendar/events}，用户确认之后逐条提交 ——
 * 把「AI 猜的」和「用户认的」分成两步，是这一整套设计的前提。
 *
 * @author crx
 * @since 2026-10-28
 */
public interface InvitationExtractService {

    /**
     * 从邀约原文（粘贴文本或文件）抽取日程候选。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         参数不合法 / 文件过大或类型不支持 / 识别过于频繁 / AI 服务异常或超时
     */
    InvitationExtractVO extract(InvitationExtractDTO dto);
}
