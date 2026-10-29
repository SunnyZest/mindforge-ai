package com.zest.mindforgeai.config;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 时间格式。
 * <p>
 * ★ <b>为什么必须显式配死，不能吃 Jackson 的默认值</b>：
 * 默认走 {@code DateTimeFormatter.ISO_LOCAL_DATE_TIME}，它<b>在秒为 0 时会把秒省掉</b>
 * （{@code 2026-11-02T15:00}，只有 16 位），秒不为 0 时才带秒，有纳秒时还会带上
 * {@code .536} 这种小数。也就是说同一个字段的字符串长度会随值变化 —— 这不是
 * 「格式」，是「没有格式」。
 * <p>
 * 前端 {@code el-date-picker} 的 {@code value-format="YYYY-MM-DDTHH:mm:ss"} 是严格解析：
 * 拿到 16 位串解析失败 → 输入框直接变空 → 那一行被判「缺时间」不能提交。
 * 用户看到的现象是「AI 没抽出时间」，而实际上时间就在响应里 —— 排查时根本想不到是格式。
 * <p>
 * 反序列化<b>刻意不动</b>：保留 Jackson 默认的宽松解析，{@code 2026-11-02T15:00}
 * 和 {@code 2026-11-02T15:00:00} 都能收。输出严格、输入宽松，是对客户端最省事的组合。
 *
 * @author crx
 * @since 2026-10-29
 */
@Configuration
public class JacksonConfig {

    /** 与前端 {@code value-format} 一致，也与 API 文档 §5.4 的约定一致 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    public static final String DATE_PATTERN = "yyyy-MM-dd";

    public static final String TIME_PATTERN = "HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsr310FormatterCustomizer() {
        DateTimeFormatter dateTime = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter date = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter time = DateTimeFormatter.ofPattern(TIME_PATTERN);

        return builder -> {
            builder.serializers(new LocalDateTimeSerializer(dateTime));
            builder.serializers(new LocalDateSerializer(date));
            builder.serializers(new LocalTimeSerializer(time));
        };
    }
}
