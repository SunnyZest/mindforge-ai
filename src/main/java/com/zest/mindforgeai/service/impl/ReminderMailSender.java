package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.properties.ReminderProperties;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import com.zest.mindforgeai.pojo.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日程提醒邮件发送。
 * <p>
 * ★ 和 {@code EmailVerificationCodeSender} 分开而不是共用一个类：
 * 验证码是纯文本、要防刷、内容里带敏感凭据；提醒是 HTML、按批发送、内容来自用户自己的日程。
 * 两者唯一的共同点是「都走 SMTP」，硬塞进一个类只会让改一边的时候提心吊胆。
 * <p>
 * ⚠️ 模板走 {@code ClassPathResource} 读一次缓存起来，不引入模板引擎 ——
 * 为了几个占位符引一个 Thymeleaf 进来不划算，而且 Thymeleaf 在邮件场景里
 * 常见的坑（上下文缺失、方言不匹配）比手写替换多得多。
 *
 * @author crx
 * @since 2026-10-28
 */
@Slf4j
@Component
public class ReminderMailSender {

    private static final String SUBJECT_PREFIX = "【MindForge AI】";

    private static final String TEMPLATE_PATH = "templates/mail/reminder.html";

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("M月d日 EEE HH:mm", Locale.CHINA);

    /** 日程类型 → 中文。前端改枚举时要同步这里 */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "WRITTEN_TEST", "笔试",
            "INTERVIEW_1", "一面",
            "INTERVIEW_2", "二面",
            "HR", "HR 面",
            "OFFER_TALK", "谈薪",
            "OTHER", "其他");

    /** 形式 → 中文 */
    private static final Map<String, String> MODE_LABELS = Map.of(
            "ONSITE", "现场",
            "ONLINE", "线上",
            "PHONE", "电话");

    private final JavaMailSender mailSender;
    private final ReminderProperties properties;
    private final String fromAddress;
    private final String template;

    public ReminderMailSender(JavaMailSender mailSender,
                              ReminderProperties properties,
                              @Value("${spring.mail.username:}") String fromAddress) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.fromAddress = fromAddress;
        this.template = loadTemplate();
    }

    /**
     * 邮件通道是否可用。
     * <p>
     * ★ 任务跑之前必须先问这一句。没配 SMTP 就发信 → 每一封都失败 → 失败次数被烧光，
     * 等运维把账号配好，那些日程已经到上限不再重试了。宁可整轮跳过。
     */
    public boolean isConfigured() {
        return StringUtils.hasText(fromAddress);
    }

    /**
     * 把一批日程合并成一封邮件发出。
     * <p>
     * ★ 为什么合并：一个用户下周可能同时有笔试、一面、HR 面。一场一封的话，
     * 他会在同一分钟收到三封主题几乎一样的邮件，第一反应是全部标为垃圾邮件。
     *
     * @param user   收件人（调用方需保证 email 非空且已验证）
     * @param events 本批日程，按时间升序
     * @throws IllegalStateException 邮件通道未配置
     * @throws MailException         SMTP 发送失败，调用方据此回滚抢占
     */
    public void sendReminder(User user, List<JobEvent> events) {
        if (!isConfigured()) {
            throw new IllegalStateException("邮件通道未配置：spring.mail.username 为空");
        }
        if (events == null || events.isEmpty()) {
            return;
        }

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            // false = 不用 multipart。纯 HTML 正文不需要附件容器，少一层结构少一个出错点
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFromName() + " <" + fromAddress + ">");
            helper.setTo(user.getEmail());
            helper.setSubject(SUBJECT_PREFIX + events.size() + " 项日程即将开始");
            helper.setText(render(user, events), true);

            mailSender.send(mime);
            log.info("日程提醒邮件已发送 to={} 条数={}", maskEmail(user.getEmail()), events.size());
        } catch (MessagingException e) {
            // MessagingException 是构造邮件阶段的问题（地址非法等），和 SMTP 无关，
            // 但对外一样是「这封信没发出去」，统一包成 MailException 让调用方只处理一种异常
            throw new MailPreparationException(e);
        }
    }

    // ---------------------------------------------------------------- 渲染

    private String render(User user, List<JobEvent> events) {
        StringBuilder items = new StringBuilder(events.size() * 256);
        for (JobEvent event : events) {
            items.append(renderItem(event));
        }

        return template
                .replace("{{nickname}}", escapeText(displayName(user)))
                .replace("{{count}}", String.valueOf(events.size()))
                .replace("{{aheadHours}}", String.valueOf(properties.getAheadHours()))
                .replace("{{items}}", items.toString())
                .replace("{{calendarUrl}}", escapeText(calendarUrl()));
    }

    private String renderItem(JobEvent event) {
        StringBuilder meta = new StringBuilder(labelOf(TYPE_LABELS, event.getEventType(), "日程"));
        if (StringUtils.hasText(event.getEventMode())) {
            meta.append(" · ").append(labelOf(MODE_LABELS, event.getEventMode(), event.getEventMode()));
        }
        if (StringUtils.hasText(event.getLocation())) {
            meta.append(" · ").append(event.getLocation());
        }

        return """
                <tr>
                  <td style="padding:14px 0;border-bottom:1px solid #eef0f3;">
                    <div style="font-size:13px;color:#1664ff;font-weight:600;">%s</div>
                    <div style="font-size:15px;font-weight:600;margin-top:4px;color:#1f2329;">%s</div>
                    <div style="font-size:13px;color:#8a9099;margin-top:4px;">%s</div>
                  </td>
                </tr>
                """.formatted(escapeText(timeLabel(event.getEventAt())),
                escapeText(event.getTitle()),
                escapeText(meta.toString()));
    }

    /**
     * 时间标签：优先说「今天 / 明天」，再补具体时间。
     * <p>
     * 只写「10月29日 周四 14:00」的话，用户还得自己算今天是几号 ——
     * 提醒邮件的价值就在「一眼知道还有多久」，别让他做心算。
     */
    private String timeLabel(LocalDateTime eventAt) {
        if (eventAt == null) {
            return "时间待定";
        }
        String text = eventAt.format(TIME_FORMATTER);
        LocalDate today = LocalDate.now();
        LocalDate day = eventAt.toLocalDate();
        if (day.equals(today)) {
            return "今天 " + text;
        }
        if (day.equals(today.plusDays(1))) {
            return "明天 " + text;
        }
        return text;
    }

    private String calendarUrl() {
        String base = properties.getSiteUrl();
        String path = properties.getCalendarPath();
        if (!StringUtils.hasText(base)) {
            return "";
        }
        if (!StringUtils.hasText(path)) {
            return base;
        }
        return base.endsWith("/") ? base + path.replaceFirst("^/", "") : base + path;
    }

    /** 昵称没填就用用户名 —— 总比「null，你有 2 项日程」强 */
    private String displayName(User user) {
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    private String labelOf(Map<String, String> labels, String code, String fallback) {
        if (!StringUtils.hasText(code)) {
            return fallback;
        }
        return labels.getOrDefault(code, code);
    }

    /**
     * HTML 转义。
     * <p>
     * ★ 日程标题、地点、备注都是<b>用户自己输入的</b>，不转义的话一个写进备注的
     * {@code <script>} 或者一个未闭合的 {@code <} 就能把整封邮件的排版打乱。
     * 邮箱客户端大多会过滤脚本，但「排版被一个尖括号搞崩」是必然发生的。
     */
    private String escapeText(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String loadTemplate() {
        try (InputStream in = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 模板是打包进 jar 的静态资源，读不到说明构建产物有问题，属于启动即该失败的错误
            throw new IllegalStateException("日程提醒邮件模板缺失：" + TEMPLATE_PATH, e);
        }
    }

    /** 日志里不留完整邮箱，和验证码发送器保持同一套脱敏口径 */
    private String maskEmail(String email) {
        if (email == null) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
