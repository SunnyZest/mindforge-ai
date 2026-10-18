package com.zest.mindforgeai.service.impl;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.constant.VerificationConstants;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.VerificationProperties;
import com.zest.mindforgeai.mapper.UserMapper;
import com.zest.mindforgeai.service.VerificationCodeSender;
import com.zest.mindforgeai.service.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 验证码服务（Redis 存储）。
 *
 * <p>★ <b>发码接口必须公开</b>（未登录的用户才需要发码），所以它天生就是
 * 「邮件轰炸 / 短信轰炸」的入口。这个类的重点不是「怎么生成码」，
 * 而是<b>三层闸门</b>：
 * <ol>
 *   <li>60 秒重发冷却（同一目标）；</li>
 *   <li>每日条数上限（同一目标）；</li>
 *   <li>同一目标同一场景只保留最新一条码，且最多试错 5 次。</li>
 * </ol>
 *
 * <p>⚠️ <b>验证码绝不进日志</b> —— 它是 10 分钟内有效的凭据，
 * 和二次确认的 confirm-token 是同一类东西。
 * <p>
 * ★ <b>找回密码只对已注册的邮箱发信</b>（但接口表现完全一样），
 * 这是个刻意的权衡，理由见 {@link #send} 里的注释。
 *
 * @author crx
 * @since 2026-10-18
 */
@Slf4j
@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final String FIELD_CODE = "code";
    private static final String FIELD_ATTEMPTS = "attempts";

    /** 验证码用 SecureRandom —— {@code Random} 是可预测的，6 位码经不起预测 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 只做「像不像邮箱」的粗校验，真正的可达性由发信结果说了算 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final StringRedisTemplate redis;
    private final VerificationProperties properties;
    private final UserMapper userMapper;
    private final List<VerificationCodeSender> senders;

    public VerificationCodeServiceImpl(StringRedisTemplate redis,
                                       VerificationProperties properties,
                                       UserMapper userMapper,
                                       List<VerificationCodeSender> senderList) {
        this.redis = redis;
        this.properties = properties;
        this.userMapper = userMapper;
        this.senders = List.copyOf(senderList);
    }

    @Override
    public void send(String channel, String target, String scene) {
        VerificationCodeSender sender = requireSender(channel);
        target = normalize(channel, target);

        // 邮箱格式在服务端也要校验一遍：DTO 里只做了「非空 + 长度」，
        // 而格式错了会让 SMTP 报一个很难看懂的错误（553 / 501）
        if (VerificationConstants.CHANNEL_EMAIL.equals(channel)
                && !EMAIL_PATTERN.matcher(target).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "邮箱格式不正确");
        }

        // ① 冷却：SETNX 成功才放行。不用「先 GET 再 SET」—— 两步之间并发能穿过去
        String cooldownKey = VerificationConstants.cooldownKey(channel, target);
        Boolean acquired = redis.opsForValue().setIfAbsent(
                cooldownKey, "1", Duration.ofSeconds(properties.getCooldownSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(ErrorCode.VERIFICATION_TOO_FREQUENT,
                    "请 " + properties.getCooldownSeconds() + " 秒后再试");
        }

        // ② 每日上限
        if (!consumeDailyQuota(channel, target)) {
            // 已经用完了，把冷却也放掉 —— 否则用户等 60 秒后重试还是失败，体验莫名其妙
            redis.delete(cooldownKey);
            throw new BizException(ErrorCode.VERIFICATION_TOO_FREQUENT,
                    "今天获取验证码的次数已达上限");
        }

        // ③ 生成并存码。
        //    ⚠️ 不管是发还是不发，这一步都要走 —— 两条路的耗时只差最后那一下 SMTP，
        //       差别越小，越难靠响应时间猜出「这个邮箱有没有注册」。
        String code = generateCode();
        String codeKey = VerificationConstants.codeKey(channel, scene, target);
        Map<String, String> value = new HashMap<>();
        value.put(FIELD_CODE, code);
        value.put(FIELD_ATTEMPTS, "0");
        redis.opsForHash().putAll(codeKey, value);
        redis.expire(codeKey, Duration.ofMinutes(properties.getTtlMinutes()));

        // ④ 找回密码：邮箱没注册过就【不发信，但照样返回成功】。
        //
        // ★ 这是一个刻意的权衡，两边都不满意、但都比「随便挑一边」好：
        //   a) 如果发 —— 谁都能用我们的服务器给任意地址发信（每地址每天 10 封），
        //      等于免费邮件炸弹。发信域名是我们的，被反垃圾组织拉黑之后，
        //      真正该收到的验证码也发不出去了。
        //   b) 如果不发但返回失败 —— 这个公开接口就成了「某个邮箱注册过没有」的探测器。
        //
        //   所以现在的做法是：不发的效果是「用户收不到信，但接口看不出区别」。
        //   ⚠️ 注册场景刻意不做这个判断 —— 新注册的邮箱本来就没可能是已注册账号，
        //      拦了会让用户永远收不到注册码。
        if (VerificationConstants.SCENE_RESET_PASSWORD.equals(scene)
                && userMapper.countByEmailActive(target) == 0) {
            log.info("找回密码：邮箱未注册，跳过发信（不暴露注册状态）scene={} target={}",
                    scene, maskTarget(channel, target));
            return;
        }

        // ⑤ 发出去。失败要把码和冷却一起撤掉 —— 否则用户既收不到信、又被锁 60 秒
        try {
            sender.send(target, code, scene);
        } catch (RuntimeException e) {
            redis.delete(codeKey);
            redis.delete(cooldownKey);
            throw e;
        }

        log.info("验证码已下发 channel={} scene={} target={}（有效期 {} 分钟）",
                channel, scene, maskTarget(channel, target), properties.getTtlMinutes());
    }

    @Override
    public void verifyAndConsume(String channel, String target, String scene, String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.VERIFICATION_CODE_INVALID);
        }
        target = normalize(channel, target);

        String codeKey = VerificationConstants.codeKey(channel, scene, target);
        Map<Object, Object> stored = redis.opsForHash().entries(codeKey);
        if (stored == null || stored.isEmpty()) {
            throw new BizException(ErrorCode.VERIFICATION_CODE_INVALID);
        }

        // ① 先计一次尝试再比对：这样即使有人用「错的码」刷，次数也会被记上
        Long attempts = redis.opsForHash().increment(codeKey, FIELD_ATTEMPTS, 1);
        if (attempts != null && attempts > properties.getMaxAttempts()) {
            // 超限直接作废这条码，逼用户重新获取（重新获取要过冷却和每日上限）
            redis.delete(codeKey);
            log.warn("验证码试错次数超限，已作废 channel={} scene={} target={}",
                    channel, scene, maskTarget(channel, target));
            throw new BizException(ErrorCode.VERIFICATION_CODE_INVALID);
        }

        String expected = String.valueOf(stored.get(FIELD_CODE));
        if (!constantTimeEquals(expected, code)) {
            throw new BizException(ErrorCode.VERIFICATION_CODE_INVALID);
        }

        // ② 原子消费：DEL 的返回值是「实际删掉了几个 key」。
        //    并发的两个请求同时校验，只有一个能拿到 true，另一个必然失败 —— 防重放。
        Boolean deleted = redis.delete(codeKey);
        if (!Boolean.TRUE.equals(deleted)) {
            throw new BizException(ErrorCode.VERIFICATION_CODE_INVALID);
        }
    }

    /* ---------- 内部工具 ---------- */

    /**
     * 归一化接收地址。
     * <p>
     * ⚠️ 邮箱统一转小写：{@code Foo@QQ.com} 和 {@code foo@qq.com} 是同一个邮箱，
     * 不归一化的话会生成两把 key —— 用户在注册页填大写、在找回密码页填小写，就收不到码。
     * 手机号只去空格和横线。
     */
    private String normalize(String channel, String target) {
        if (target == null) {
            return "";
        }
        String trimmed = target.trim();
        if (VerificationConstants.CHANNEL_EMAIL.equals(channel)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.replace(" ", "").replace("-", "");
    }

    /**
     * 按通道找实现。
     * <p>
     * ⚠️ 刻意<b>不在构造函数里建 Map</b>：那样 {@code channel()} 会在容器启动时被调用，
     * 而单测里通道是 mock 的（默认返回 null），启动就直接炸了 ——
     * 报错还长得像「上下文加载失败」，跟验证码看不出关系。
     * 发送是低频操作，线性扫一遍（1~2 个实现）没有任何性能问题。
     */
    private VerificationCodeSender requireSender(String channel) {
        if (!VerificationConstants.CHANNELS.contains(channel)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的验证码通道：" + channel);
        }
        for (VerificationCodeSender sender : senders) {
            if (channel.equals(sender.channel())) {
                return sender;
            }
        }
        // 通道在常量里登记了，但没有实现类（比如短信还没接）
        throw new BizException(ErrorCode.PARAM_INVALID, "该验证码通道尚未开通：" + channel);
    }

    /** 返回 false 表示今天已经用满 */
    private boolean consumeDailyQuota(String channel, String target) {
        String dailyKey = VerificationConstants.dailyKey(channel, target);
        Long count = redis.opsForValue().increment(dailyKey);
        if (count != null && count == 1L) {
            // 第一次才设过期 —— 每次都设会把 TTL 一直往后推，等于永不归零
            redis.expire(dailyKey, Duration.ofDays(1));
        }
        return count == null || count <= properties.getDailyLimit();
    }

    /** 6 位数字，前导零要保留（{@code String.format} 而不是 {@code String.valueOf}） */
    private String generateCode() {
        int bound = (int) Math.pow(10, properties.getCodeLength());
        int value = RANDOM.nextInt(bound);
        return String.format("%0" + properties.getCodeLength() + "d", value);
    }

    /**
     * 定长比较，避免通过响应时间差猜码。
     * <p>
     * 6 位码的搜索空间只有 100 万，配合 5 次试错上限其实很难爆破；
     * 但这是零成本的好习惯，没有理由不做。
     */
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 日志脱敏：邮箱只留首字母 + 域名，手机号只留后四位 */
    private String maskTarget(String channel, String target) {
        if (VerificationConstants.CHANNEL_EMAIL.equals(channel)) {
            int at = target.indexOf('@');
            return at <= 1 ? "***" : target.charAt(0) + "***" + target.substring(at);
        }
        return target.length() <= 4 ? "***" : "***" + target.substring(target.length() - 4);
    }
}
