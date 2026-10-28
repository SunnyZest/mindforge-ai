package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.properties.ReminderProperties;
import com.zest.mindforgeai.pojo.entity.JobEvent;
import com.zest.mindforgeai.pojo.entity.User;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提醒邮件的内容渲染。
 *
 * <p>★ 为什么单独一个不启 Spring 的用例：{@code ReminderServiceTest} 把 sender 整个 mock 掉了，
 * 它验证的是「该不该发」；而「发出去的那封信长什么样」完全没被覆盖 ——
 * 模板里少写一个占位符、转义漏了，那个测试照样全绿，用户收到的却是一封带 <code>{{nickname}}</code>
 * 或者被一个尖括号撑破排版的邮件。
 *
 * <p>做法：mock 掉 {@link JavaMailSender} 的 {@code createMimeMessage}（返回真 MimeMessage）
 * 和 {@code send}（什么也不做），然后把真正构造出来的信截下来检查。
 *
 * @author crx
 * @since 2026-10-28
 */
class ReminderMailSenderTest {

    private JavaMailSender mailSender;
    private ReminderMailSender sender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        ReminderProperties properties = new ReminderProperties();
        properties.setSiteUrl("https://mf.example.com");
        properties.setCalendarPath("/jobs");

        sender = new ReminderMailSender(mailSender, properties, "noreply@example.com");
    }

    @Test
    @DisplayName("渲染出的邮件不留占位符，用户输入被转义，链接指向配置的站点")
    void rendersTemplateWithoutLeftovers() throws Exception {
        User user = new User().setUsername("u1").setNickname("张三").setEmail("u1@example.com");
        JobEvent event = new JobEvent()
                .setId(1L)
                // 故意塞一段标签：备注和标题都是用户自己输入的，不转义就能把排版搞崩
                .setTitle("<script>alert(1)</script> 技术一面")
                .setEventType("INTERVIEW_1")
                .setEventMode("ONLINE")
                .setLocation("腾讯会议 123-456")
                .setEventAt(LocalDate.now().plusDays(1).atTime(14, 0));

        sender.sendReminder(user, List.of(event));

        MimeMessage sent = capture();
        String html = (String) sent.getContent();

        // 占位符必须全被替换掉 —— 漏一个就是用户看到 {{nickname}} 这种原始标记
        assertThat(html).doesNotContain("{{");
        // 转义：尖括号变成实体，不能原样出现在正文里
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("张三");
        assertThat(html).contains("https://mf.example.com/jobs");
        assertThat(sent.getSubject()).contains("1 项日程即将开始");
    }

    @Test
    @DisplayName("明天的日程在时间标签上直接写「明天」，用户不用自己算日期")
    void labelsTomorrowExplicitly() throws Exception {
        User user = new User().setUsername("u2").setEmail("u2@example.com");
        JobEvent event = new JobEvent()
                .setId(2L)
                .setTitle("笔试")
                .setEventType("WRITTEN_TEST")
                .setEventAt(LocalDate.now().plusDays(1).atTime(9, 30));

        sender.sendReminder(user, List.of(event));

        String html = (String) capture().getContent();
        assertThat(html).contains("明天");
        assertThat(html).contains("笔试");
        // 昵称为空时用用户名兜底，不能出现「null，未来 24 小时内…」
        assertThat(html).contains("u2");
        assertThat(html).doesNotContain("null");
    }

    private MimeMessage capture() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
