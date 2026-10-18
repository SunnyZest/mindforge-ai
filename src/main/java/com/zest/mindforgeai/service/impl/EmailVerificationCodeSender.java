package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.VerificationConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.VerificationProperties;
import com.zest.mindforgeai.service.VerificationCodeSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 邮箱验证码发送（标准 SMTP）。
 *
 * <p>★ 为什么用 SMTP 而不是某家服务商的 SDK：换服务商只改配置，代码不动。
 * QQ 邮箱、阿里云邮件推送、腾讯云 SES、企业邮、Resend 都支持 SMTP。
 *
 * <p>⚠️ <b>QQ 邮箱的三个坑</b>（都会表现为「发送失败」）：
 * <ol>
 *   <li>密码位要填<b>授权码</b>（16 位），不是 QQ 登录密码；</li>
 *   <li>要先去邮箱设置里<b>开启 SMTP 服务</b>，授权码是开启后才生成的；</li>
 *   <li><b>From 必须等于登录账号</b> —— QQ 不允许用别的地址发信，否则 553 报错。
 *       所以这里 From 固定取 {@code spring.mail.username}，显示名才可配。</li>
 * </ol>
 *
 * @author crx
 * @since 2026-10-18
 */
@Slf4j
@Component
public class EmailVerificationCodeSender implements VerificationCodeSender {

    private static final String SUBJECT_PREFIX = "【MindForge AI】";

    private final JavaMailSender mailSender;
    private final VerificationProperties properties;
    private final String fromAddress;

    public EmailVerificationCodeSender(JavaMailSender mailSender,
                                       VerificationProperties properties,
                                       @Value("${spring.mail.username:}") String fromAddress) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.fromAddress = fromAddress;
    }

    @Override
    public String channel() {
        return VerificationConstants.CHANNEL_EMAIL;
    }

    @Override
    public void send(String target, String code, String scene) {
        // 没配账号就明确报错。不静默失败 —— 否则用户等半天收不到信，也看不到任何异常
        if (!StringUtils.hasText(fromAddress)) {
            log.error("邮件通道未配置：spring.mail.username 为空，验证码无法发送");
            throw new BizException(ErrorCode.EMAIL_SEND_FAILED, "邮件服务未配置，请联系管理员");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromName() + " <" + fromAddress + ">");
        message.setTo(target);
        message.setSubject(SUBJECT_PREFIX + subjectOf(scene));
        message.setText(bodyOf(code, scene));

        try {
            mailSender.send(message);
            // ⚠️ 只打场景和「发给了谁」，不打 code
            log.info("验证码邮件已发送 scene={} to={}", scene, maskEmail(target));
        } catch (MailException e) {
            // 同样不打 code；异常本身可能含 SMTP 应答，但那里面没有验证码
            log.error("验证码邮件发送失败 scene={} to={}", scene, maskEmail(target), e);
            throw new BizException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    private String subjectOf(String scene) {
        return VerificationConstants.SCENE_RESET_PASSWORD.equals(scene)
                ? "重置密码验证码"
                : "注册验证码";
    }

    /**
     * 邮件正文。
     *
     * <p>⚠️ 排版有个坑，改文案时别踩：很多邮箱客户端的<b>列表页摘要</b>会把换行去掉、
     * 再交给自己的「验证码识别」抓一串数字。如果码后面紧跟着另一个数字，
     * 抓出来的就是两个数字连起来的长度 —— 用户会在列表里看到一个 8 位的「验证码」，
     * 点进去才是真的 6 位。
     *
     * <p>所以这里刻意用「本验证码 10 分钟内有效」而不是「10 分钟内有效」：
     * 中间隔了汉字，无论客户端怎么折叠空白，码和数字都连不成一串。
     * 那条 4 空格缩进只是视觉上让码更显眼，不指望它来分隔。
     */
    private String bodyOf(String code, String scene) {
        String action = VerificationConstants.SCENE_RESET_PASSWORD.equals(scene)
                ? "重置密码"
                : "注册账号";
        return """
                你正在%s，验证码：

                    %s

                本验证码 %d 分钟内有效。如果不是你本人操作，请忽略这封邮件 —— 你的账号不会有任何变化。
                请勿把验证码告诉任何人，我们的工作人员不会向你索要它。
                """.formatted(action, code, properties.getTtlMinutes());
    }

    /**
     * 邮箱脱敏：日志里只留首字母和域名。
     * <p>
     * 完整邮箱也是个人信息，日志会被采集、会被更多人看到，没必要留全。
     */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}