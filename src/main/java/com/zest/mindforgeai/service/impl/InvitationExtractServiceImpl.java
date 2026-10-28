package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.JobMapper;
import com.zest.mindforgeai.pojo.dto.InvitationExtractDTO;
import com.zest.mindforgeai.pojo.entity.Job;
import com.zest.mindforgeai.pojo.vo.ExtractedJobEventVO;
import com.zest.mindforgeai.pojo.vo.InvitationExtractVO;
import com.zest.mindforgeai.service.InvitationExtractService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面试邀约抽取实现。
 * <p>
 * ★ <b>这个类的核心不是「调 AI」，而是「不信 AI」。</b>
 * 模型返回的每个字段都要过三道闸，任何一道不过就当它没说过：
 * <ol>
 *   <li><b>evidence 子串校验（硬闸）</b>：模型对每个字段回一段「支撑该值的原文片段」，
 *       这里校验 {@code 原文.contains(片段)}，不过就丢弃该字段。这条不依赖模型听话 ——
 *       即使它编了一个不存在的面试时间，片段在原文里也找不到，照样被扔掉。</li>
 *   <li><b>枚举与区间钳制</b>：{@code eventType} / {@code eventMode} 必须在白名单里，
 *       {@code durationMinutes} 必须在 0~1440，越界一律置 null，不猜。</li>
 *   <li><b>时间可解析</b>：解析不了就是 null。绝不「大概是今天吧」。</li>
 * </ol>
 * 唯一允许推导的是「明天」「下周三」这类相对表述的换算，且必须由 Python 侧
 * 回 {@code eventAtDerived = true}，前端标黄让人核对 —— 换算错一小时，
 * 邮件提醒就会在错的时间发出去。
 *
 * @author crx
 * @since 2026-10-28
 */
@Slf4j
@Service
public class InvitationExtractServiceImpl implements InvitationExtractService {

    /** 限流 key 前缀：按<b>用户</b>计，不按 IP —— 同一个办公室的两个人不该互相挤占额度 */
    private static final String PREFIX_QUOTA = "mf:invitation:extract:u:";

    /**
     * 每用户每小时的识别次数。
     * <p>
     * 实测正常用量一天 1~3 次（收到邀约才用一次），20 次/小时足够覆盖
     * 「一次邀约反复调格式试几遍」的场景，同时挡住脚本刷 AI 额度。
     */
    private static final int MAX_PER_HOUR = 20;

    /** 粘贴文本的字符上限，与 {@link InvitationExtractDTO#getText()} 的 {@code @Size} 保持一致 */
    private static final int MAX_TEXT_CHARS = 20_000;

    /** 文件字节上限，与前端一致 */
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    /**
     * 单次最多接受的候选条数。
     * <p>
     * 一封邀约撑死三五轮。给 20 是防「模型把整段文字里每句话都当成一条日程」
     * 这种输出失控 —— 真发生了，用户在一个 50 行的预览表里逐条取消，
     * 体验比报错还差。
     */
    private static final int MAX_EVENTS = 20;

    /** 证据片段的最短长度：1 个字符（比如「1」）几乎必然能在原文里找到，等于没校验 */
    private static final int MIN_EVIDENCE_LEN = 2;

    /**
     * 邀约时间统一落在这个时区。
     * <p>
     * 项目是面向国内求职者的，日程就是「北京时间几点」。ICS 里的 {@code Z} 是 UTC，
     * 差 8 小时会直接体现在邮件提醒的发送时间上，所以带时区的输入一律换算到这里。
     */
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Set<String> ALLOWED_FILE_TYPES =
            Set.of("PDF", "DOCX", "TXT", "MD", "PNG", "JPG");

    /** 走 OCR 的类型 —— 只用来决定要不要提示「识别可能有误差」 */
    private static final Set<String> IMAGE_FILE_TYPES = Set.of("PNG", "JPG");

    /** 与 {@code JobEventSaveDTO.eventType} 的白名单一致 */
    private static final Set<String> EVENT_TYPES =
            Set.of("WRITTEN_TEST", "INTERVIEW_1", "INTERVIEW_2", "HR", "OFFER_TALK", "OTHER");

    /** 与 {@code JobEventSaveDTO.eventMode} 的白名单一致 */
    private static final Set<String> EVENT_MODES = Set.of("ONSITE", "ONLINE", "PHONE");

    /**
     * 公司名里常见的法律形式后缀。
     * <p>
     * 邀约原文常写「字节跳动」，而用户录职位时可能写「北京字节跳动科技有限公司」，
     * 不剥掉这些后缀，「北京字节跳动科技」和「字节跳动」就永远对不上。
     * <p>
     * ⚠️ 刻意<b>不</b>剥「集团」以外的行业词（科技 / 网络 / 信息）——
     * 剥了会让「小米科技」和「小米网络」变成同一家，那是另一种错。
     */
    private static final String[] COMPANY_SUFFIXES = {
            "股份有限公司", "有限责任公司", "有限公司", "集团", "公司",
    };

    /** 各字段的落库长度上限（对齐 job_event 列宽），超长直接截断而不是报错 */
    private static final int MAX_TITLE_LEN = 255;
    private static final int MAX_COMPANY_LEN = 255;
    private static final int MAX_POSITION_LEN = 255;
    private static final int MAX_LOCATION_LEN = 500;
    private static final int MAX_URL_LEN = 500;
    private static final int MAX_INTERVIEWER_LEN = 100;
    /** 备注给 500：前端会把「面试官 / 会议链接 / 地点」一起拼进 note，而 note 列上限是 1000，要留余量 */
    private static final int MAX_NOTE_LEN = 500;

    private final AiServiceClient aiServiceClient;
    private final StringRedisTemplate redis;
    private final JobMapper jobMapper;

    public InvitationExtractServiceImpl(AiServiceClient aiServiceClient,
                                        StringRedisTemplate redis,
                                        JobMapper jobMapper) {
        this.aiServiceClient = aiServiceClient;
        this.redis = redis;
        this.jobMapper = jobMapper;
    }

    @Override
    public InvitationExtractVO extract(InvitationExtractDTO dto) {
        Long userId = SecurityUtils.getUserId();
        boolean byFile = "FILE".equalsIgnoreCase(dto.getSource());

        // 先校验入参再扣额度：参数错是白费，不该占用户的次数
        String fileType = byFile ? normalizeFileType(dto.getFileType()) : null;
        String text = byFile ? null : trimToNull(dto.getText());
        validateInput(byFile, fileType, text, dto.getContentBase64());

        checkQuota(userId);

        AiServiceClient.InvitationExtractData data = aiServiceClient.extractInvitation(
                new AiServiceClient.InvitationExtractRequest(
                        String.valueOf(userId),
                        text,
                        fileType,
                        byFile ? dto.getContentBase64() : null));

        // 文件输入时 Java 手上没有原文（PDF / DOCX / 图片只有 Python 能解析），
        // 只能用 Python 回传的 sourceText 做证据校验。
        String sourceText = byFile ? data.sourceText() : text;
        if (!StringUtils.hasText(sourceText)) {
            // 没有原文 = 证据闸失效。与其放行一堆未经校验的字段，不如直接报错 ——
            // 这是 Python 侧的契约违例，必须显式暴露出来。
            log.error("邀约抽取没有拿到原文，无法做证据校验 userId={} byFile={}", userId, byFile);
            throw new BizException(ErrorCode.AI_SERVICE_ERROR, "识别结果缺少原文，无法校验，请稍后重试");
        }

        String normalizedSource = normalizeForMatch(sourceText);
        List<Job> jobs = loadJobs(userId);

        List<ExtractedJobEventVO> events = new ArrayList<>();
        Stats stats = new Stats();
        List<AiServiceClient.ExtractedEvent> rawEvents =
                data.events() == null ? List.of() : data.events();

        for (AiServiceClient.ExtractedEvent raw : rawEvents) {
            if (events.size() >= MAX_EVENTS) {
                log.warn("邀约抽取返回超过 {} 条，已截断 userId={}", MAX_EVENTS, userId);
                stats.truncatedEvents = true;
                break;
            }
            if (raw == null) {
                continue;
            }
            events.add(toVO(raw, normalizedSource, jobs, stats));
        }

        String warning = buildWarning(byFile, fileType, events, stats,
                Boolean.TRUE.equals(data.truncated()));

        log.info("邀约抽取完成 userId={} byFile={} 原文={}字 候选={}条 丢弃字段={}处",
                userId, byFile, countChars(sourceText), events.size(), stats.droppedFields);

        return InvitationExtractVO.of(events, countChars(sourceText), warning);
    }

    // ---------- 单条转换 ----------

    private ExtractedJobEventVO toVO(AiServiceClient.ExtractedEvent raw,
                                     String normalizedSource,
                                     List<Job> jobs,
                                     Stats stats) {

        EvidenceGate gate = new EvidenceGate(normalizedSource, raw.evidence());

        ExtractedJobEventVO vo = new ExtractedJobEventVO();
        vo.setTitle(cut(gate.accept("title", raw.title()), MAX_TITLE_LEN));
        vo.setCompanyName(cut(gate.accept("companyName", raw.companyName()), MAX_COMPANY_LEN));
        vo.setPositionName(cut(gate.accept("positionName", raw.positionName()), MAX_POSITION_LEN));
        vo.setEventType(normalizeEnum(gate.accept("eventType", raw.eventType()), EVENT_TYPES));
        vo.setEventMode(normalizeEnum(gate.accept("eventMode", raw.eventMode()), EVENT_MODES));
        vo.setLocation(cut(gate.accept("location", raw.location()), MAX_LOCATION_LEN));
        vo.setDurationMinutes(parseDuration(gate.accept("durationMinutes",
                raw.durationMinutes() == null ? null : String.valueOf(raw.durationMinutes()))));

        LocalDateTime eventAt = parseEventAt(gate.accept("eventAt", raw.eventAt()));
        vo.setEventAt(eventAt);
        // eventAt 被丢了，derived 也就没有意义了 —— 不能让前端对着空时间显示「已换算」
        vo.setEventAtDerived(eventAt != null && Boolean.TRUE.equals(raw.eventAtDerived()));

        // 这三个字段不在前端 ExtractedEventField 的证据契约里（模型不对它们回 evidence），
        // 所以只做长度截断；会议链接额外做一次主机名比对，避免模型编一个假地址让人进错会。
        vo.setMeetingUrl(keepMeetingUrl(raw.meetingUrl(), normalizedSource));
        vo.setInterviewer(cut(trimToNull(raw.interviewer()), MAX_INTERVIEWER_LEN));
        vo.setNote(cut(trimToNull(raw.note()), MAX_NOTE_LEN));

        vo.setEvidence(gate.keptEvidence());

        matchJob(vo, jobs);

        stats.droppedFields += gate.droppedCount();
        if (vo.getEventAt() == null) {
            stats.missingTime++;
        } else {
            if (vo.isEventAtDerived()) {
                stats.derivedTime++;
            }
            if (vo.getEventAt().isBefore(LocalDateTime.now())) {
                stats.pastTime++;
            }
        }
        if (vo.getMatchedJobId() == null) {
            stats.unmatchedJob++;
        }
        return vo;
    }

    /**
     * 把候选日程匹配到用户已有的职位。
     * <p>
     * ★ <b>匹配不上是正常结果，不是失败</b>。前端会要求用户从已有职位里选一个，
     * 而不是拿邀约原文去建一个新职位 —— 原文当 JD 会让 AI 解析出一堆无意义技能，
     * 把技能图谱污染掉，后面匹配分析全跟着错。
     * <p>
     * 匹配规则按「公司是强信号、岗位是弱信号」：
     * <ul>
     *   <li>公司对上的候选里只有一条 → 直接用它（邀约常把岗位写成「后端开发」，
     *       而用户录的是「高级 Java 工程师」，硬要求岗位也对上会大面积匹配失败）；</li>
     *   <li>公司对上多条 → 再用岗位筛；筛完仍不唯一，<b>返回 null 让用户选</b>，
     *       不按更新时间猜一个；</li>
     *   <li>公司没对上但岗位唯一命中 → 用那条（原文可能只写了岗位没写公司）。</li>
     * </ul>
     */
    private void matchJob(ExtractedJobEventVO vo, List<Job> jobs) {
        String company = normalizeCompany(vo.getCompanyName());
        String position = normalizeForMatch(vo.getPositionName());

        List<Job> companyHits = new ArrayList<>();
        if (company.length() >= MIN_EVIDENCE_LEN) {
            for (Job job : jobs) {
                if (companyHit(company, normalizeCompany(job.getCompanyName()))) {
                    companyHits.add(job);
                }
            }
        }

        if (companyHits.size() == 1) {
            pick(vo, companyHits.get(0));
            return;
        }

        if (companyHits.size() > 1) {
            if (position.length() >= MIN_EVIDENCE_LEN) {
                List<Job> both = new ArrayList<>();
                for (Job job : companyHits) {
                    if (positionHit(position, normalizeForMatch(job.getTitle()))) {
                        both.add(job);
                    }
                }
                if (both.size() == 1) {
                    pick(vo, both.get(0));
                }
            }
            return;
        }

        // 公司没对上：只有「岗位唯一命中」才敢认，两条以上一律不猜
        if (position.length() < MIN_EVIDENCE_LEN) {
            return;
        }
        List<Job> positionHits = new ArrayList<>();
        for (Job job : jobs) {
            if (positionHit(position, normalizeForMatch(job.getTitle()))) {
                positionHits.add(job);
            }
        }
        if (positionHits.size() == 1) {
            pick(vo, positionHits.get(0));
        }
    }

    private void pick(ExtractedJobEventVO vo, Job job) {
        vo.setMatchedJobId(String.valueOf(job.getId()));
        vo.setMatchedJobTitle(job.getTitle());
    }

    /** 公司名比对：任一方包含另一方即算命中（「字节跳动」 vs 「北京字节跳动科技」） */
    private boolean companyHit(String a, String b) {
        return a.length() >= MIN_EVIDENCE_LEN && b.length() >= MIN_EVIDENCE_LEN
                && (a.contains(b) || b.contains(a));
    }

    /** 岗位名比对：同样用包含关系（「Java 工程师」 vs 「高级 Java 工程师」） */
    private boolean positionHit(String a, String b) {
        return a.length() >= MIN_EVIDENCE_LEN && b.length() >= MIN_EVIDENCE_LEN
                && (a.contains(b) || b.contains(a));
    }

    // ---------- 入参校验与限流 ----------

    /**
     * 入参兜底校验。
     * <p>
     * DTO 上已经有 {@code @AssertTrue} 挡在入口，这里是防「绕过校验直接调 service」的
     * 兜底 —— 同 {@code LoginAttemptServiceImpl} 里截断用户名的写法。
     */
    private void validateInput(boolean byFile, String fileType, String text, String contentBase64) {
        if (!byFile) {
            if (!StringUtils.hasText(text)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请先粘贴邀约内容");
            }
            if (text.length() > MAX_TEXT_CHARS) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        String.format("内容最长 %d 个字符（当前 %d），请只粘贴邀约相关的部分",
                                MAX_TEXT_CHARS, text.length()));
            }
            return;
        }

        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "只支持 PDF / DOCX / TXT / MD / PNG / JPG");
        }
        if (!StringUtils.hasText(contentBase64)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "上传文件时请提供文件内容");
        }
        // 不真解码：按 base64 长度估字节数（4 字符 → 3 字节），避免为了一次校验
        // 在内存里再复制一份 10MB 的字节数组
        long estimatedBytes = (long) contentBase64.length() * 3 / 4;
        if (estimatedBytes > MAX_FILE_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    String.format("文件不能超过 %dMB", MAX_FILE_BYTES / 1024 / 1024));
        }
    }

    /**
     * 每用户每小时的识别次数限制。
     * <p>
     * ★ 计数在<b>调 AI 之前</b>就 +1，失败也照样占额度 —— 这是刻意的：
     * 额度防的是 AI 调用成本，不是「成功的次数」。如果只在成功时计数，
     * 一个把请求打到超时的脚本可以无限消耗模型 token。
     * <p>
     * Redis 不可用时放行（fail-open），与 {@code LoginAttemptServiceImpl} 同一套取舍：
     * 限流组件故障不该升级成「功能全挂」。
     */
    private void checkQuota(Long userId) {
        String key = PREFIX_QUOTA + userId;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return;
            }
            // 只在第一次设过期，后续不续期 —— 否则一直用的用户永远等不到窗口重置
            if (count == 1L) {
                redis.expire(key, Duration.ofHours(1));
            }
            if (count > MAX_PER_HOUR) {
                log.warn("邀约识别触发限流 userId={} count={}", userId, count);
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                        String.format("识别次数太频繁，每小时最多 %d 次，请稍后再试", MAX_PER_HOUR));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("邀约识别限流检查失败（Redis 不可用？），本次放行 userId={}", userId, e);
        }
    }

    // ---------- 提示文案 ----------

    private String buildWarning(boolean byFile, String fileType,
                                List<ExtractedJobEventVO> events, Stats stats, boolean truncated) {
        List<String> warnings = new ArrayList<>();

        if (truncated) {
            warnings.add("原文较长，只分析了前面一部分，后面可能有遗漏");
        }
        if (byFile && IMAGE_FILE_TYPES.contains(fileType)) {
            warnings.add("图片是文字识别（OCR）出来的，可能有错字，请逐项核对");
        }

        if (events.isEmpty()) {
            if (stats.droppedFields > 0) {
                warnings.add("识别出的内容在原文里都找不到依据，已全部丢弃 —— "
                        + "这段文字可能不是面试邀约，或者图片识别有误");
            } else {
                warnings.add("没有识别出明确的日程 —— 原文里可能没有具体时间，建议手动新建");
            }
        } else {
            if (stats.droppedFields > 0) {
                warnings.add(String.format("有 %d 处内容在原文里找不到依据，已丢弃，请手动补充",
                        stats.droppedFields));
            }
            if (stats.derivedTime > 0) {
                warnings.add(String.format(
                        "有 %d 条时间是按「明天」「下周三」这类说法推算的，请核对", stats.derivedTime));
            }
            if (stats.missingTime > 0) {
                warnings.add(String.format("有 %d 条日程没识别出时间，需要手动填写后才能导入",
                        stats.missingTime));
            }
            if (stats.unmatchedJob > 0) {
                warnings.add(String.format("有 %d 条日程没匹配到已有职位，需要手动选择",
                        stats.unmatchedJob));
            }
            if (stats.pastTime > 0) {
                warnings.add(String.format("有 %d 条日程的时间早于当前时间，请确认是不是往期邀约",
                        stats.pastTime));
            }
        }
        if (stats.truncatedEvents) {
            warnings.add(String.format("候选超过 %d 条，只保留了前面这些", MAX_EVENTS));
        }

        return warnings.isEmpty() ? null : cut(String.join("；", warnings), 500);
    }

    // ---------- 解析与归一 ----------

    /**
     * 时间解析。接受 {@code 2026-10-30T14:00}、{@code 2026-10-30 14:00:00}，
     * 以及带时区的 {@code 2026-10-30T14:00:00+08:00} / {@code 2026-10-30T06:00:00Z}。
     * <p>
     * ⚠️ <b>带时区时必须换算到 {@code Asia/Shanghai}，不能直接取本地部分。</b>
     * ICS 日历里的时间是 UTC（结尾的 {@code Z}），直接 {@code toLocalDateTime()} 会
     * 整整差 8 小时 —— 表现是「邮件提醒提前 8 小时发出去」，而用户完全不知道是时区问题。
     * 契约上 Python 应该已经把时间转成本地时间再回传，这里是<b>兜底</b>：
     * 万一它漏了，Java 也不会把 UTC 当成本地时间用。
     * <p>
     * 不带时区的字符串（正常情况）原样解析，不做任何换算。解析不了就返回 null，不猜。
     */
    private LocalDateTime parseEventAt(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            // 不是纯本地时间，继续尝试带时区的写法
        }
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZONE_SHANGHAI)
                    .toLocalDateTime();
        } catch (Exception e) {
            log.debug("邀约里的时间解析不了，按「原文没写」处理：{}", raw);
            return null;
        }
    }

    private Integer parseDuration(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            int minutes = Integer.parseInt(raw.trim());
            // 0 分钟的日程没有意义，1440 分钟（24 小时）是列上约定的上限
            return (minutes <= 0 || minutes > 1440) ? null : minutes;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeEnum(String raw, Set<String> allowed) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (allowed.contains(value)) {
            return value;
        }
        log.debug("邀约里的枚举值不在白名单内，按「原文没写」处理：{}", raw);
        return null;
    }

    private String normalizeFileType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        // 前端把 JPEG 统一成 JPG 再传，这里再兜一次（别的客户端未必这么做）
        return "JPEG".equals(value) ? "JPG" : value;
    }

    /**
     * 比对用的归一化：NFKC（全角转半角）+ 去掉所有空白 + 转小写。
     * <p>
     * 模型回 evidence 时常把换行抹平、把全角数字写成半角，而原文里是另一副样子。
     * 两边用同一套规则归一之后再比子串，才能既拦住编造的内容、又不误杀真实片段。
     */
    private static String normalizeForMatch(String raw) {
        if (raw == null) {
            return "";
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00a0' || c == '\u3000') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 公司名归一：在通用归一的基上再剥掉法律形式后缀 */
    private String normalizeCompany(String raw) {
        String value = normalizeForMatch(raw);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String suffix : COMPANY_SUFFIXES) {
                if (value.endsWith(suffix) && value.length() > suffix.length() + 1) {
                    value = value.substring(0, value.length() - suffix.length());
                    stripped = true;
                }
            }
        }
        return value;
    }

    /**
     * 会议链接的弱校验：主机名在原文里出现过才保留。
     * <p>
     * 不按整串比对，是因为模型常给链接补 {@code https://} 或去掉尾部参数，
     * 严格比对会把合法链接误删；而主机名对不上，基本就是编的。
     */
    private String keepMeetingUrl(String raw, String normalizedSource) {
        String url = cut(trimToNull(raw), MAX_URL_LEN);
        if (url == null) {
            return null;
        }
        String host = hostOf(url);
        if (host != null && normalizedSource.contains(normalizeForMatch(host))) {
            return url;
        }
        if (normalizedSource.contains(normalizeForMatch(url))) {
            return url;
        }
        log.debug("会议链接在原文里找不到对应主机，已丢弃：{}", url);
        return null;
    }

    private static String hostOf(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
            return new URI("http://" + url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 小工具 ----------

    private List<Job> loadJobs(Long userId) {
        return jobMapper.selectList(new LambdaQueryWrapper<Job>()
                .select(Job::getId, Job::getTitle, Job::getCompanyName)
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, 1)
                .orderByDesc(Job::getUpdatedAt)
                // 一个人不太可能同时跟进 200 个职位；真到了这个量级，老职位也早不该被匹配上
                .last("LIMIT 200"));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 有效字符数：不含空白，前端拿来显示「已读取 N 字」 */
    private static int countChars(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u00a0' && c != '\u3000') {
                count++;
            }
        }
        return count;
    }

    /** 一次抽取的统计，只用于拼提示文案 */
    private static final class Stats {
        private int droppedFields;
        private int missingTime;
        private int derivedTime;
        private int unmatchedJob;
        private int pastTime;
        private boolean truncatedEvents;
    }

    /**
     * 证据闸。
     * <p>
     * 每条候选日程建一个：同一个 {@code evidence} map 只服务一条日程，
     * 跨条复用会把 A 条的证据算到 B 条头上。
     */
    private static final class EvidenceGate {

        private final String normalizedSource;
        private final Map<String, String> evidence;
        private final Map<String, String> kept = new LinkedHashMap<>();
        private int droppedCount;

        EvidenceGate(String normalizedSource, Map<String, String> evidence) {
            this.normalizedSource = normalizedSource;
            this.evidence = evidence == null ? Map.of() : evidence;
        }

        /**
         * 校验一个字段：值非空、证据片段非空、片段确实出现在原文里，三者都满足才放行。
         * <p>
         * 任何一条不满足都返回 null —— 对调用方来说语义统一为「这个字段没有」，
         * 不需要再区分「模型没给」和「模型瞎给」。
         */
        String accept(String field, String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            String snippet = normalizeForMatch(evidence.get(field));
            if (snippet.length() < MIN_EVIDENCE_LEN || !normalizedSource.contains(snippet)) {
                droppedCount++;
                return null;
            }
            kept.put(field, evidence.get(field));
            return value.trim();
        }

        /**
         * 通过校验的字段与它们的证据片段。
         * <p>
         * 一个都没通过时返回<b>空 map 而不是 null</b>：前端类型里 {@code evidence} 是
         * {@code Partial<Record<...>>}（对象），不是可空值，给 null 会让那边的
         * {@code Object.keys(evidence)} 直接抛错。
         */
        Map<String, String> keptEvidence() {
            return kept;
        }

        int droppedCount() {
            return droppedCount;
        }
    }
}
