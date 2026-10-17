package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zest.mindforgeai.client.AiServiceClient;
import com.zest.mindforgeai.client.AiServiceClient.SalaryEstimateData;
import com.zest.mindforgeai.client.AiServiceClient.SalaryEstimateRequest;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.mapper.SalaryEstimateMapper;
import com.zest.mindforgeai.pojo.dto.SalaryEstimateDTO;
import com.zest.mindforgeai.pojo.entity.SalaryEstimate;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.SalaryEstimateVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.SalaryEstimateService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 薪酬估算实现。
 *
 * <p>★ <b>这个功能最该防的是「看起来像真的」</b>：
 * <ul>
 *   <li>没有任何真实薪酬样本 —— 所以不造「样本量 / 数据来源」字段；</li>
 *   <li>模型自评置信度普遍偏高 —— 所以服务端按「城市 / 岗位 / 年限是否常见」再钳一次；</li>
 *   <li>免责声明不交给模型写 —— 交给它，总会有某次生成悄悄省掉。</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-17
 */
@Slf4j
@Service
public class SalaryEstimateServiceImpl
        extends ServiceImpl<SalaryEstimateMapper, SalaryEstimate>
        implements SalaryEstimateService {

    /** 同参数结果复用窗口。7 天是个折中：再短就要重复烧 token，再长就跟不上行情变化 */
    private static final int CACHE_DAYS = 7;

    /** 历史分页上限，防止前端传个超大 pageSize 拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 50;

    /** 月数的合理范围 —— 超出这个区间的返回值只可能是模型算错了 */
    private static final int MIN_MONTHS = 12;
    private static final int MAX_MONTHS = 16;

    /** 免责声明：固定文案，服务端拼接，不采信模型输出 */
    private static final String CAVEAT =
            "以上为 AI 根据岗位画像推算的参考区间，不是真实薪酬样本，也不能作为谈薪或接受 Offer 的依据。"
                    + "实际薪资受公司、职级、面试表现与谈判影响很大，请以对方给出的书面数字为准。";

    /** 常见城市 —— 不在这份名单里的城市，估算参考价值明显下降，置信度要降档 */
    private static final Set<String> COMMON_CITIES = Set.of(
            "北京", "上海", "深圳", "广州", "杭州", "成都", "南京", "武汉", "西安", "苏州",
            "天津", "重庆", "长沙", "郑州", "合肥", "厦门", "青岛", "大连", "宁波", "无锡",
            "福州", "济南", "东莞", "佛山", "珠海", "昆明", "沈阳", "南昌", "贵阳", "石家庄",
            "太原", "长春", "哈尔滨", "南宁", "海口", "兰州", "乌鲁木齐", "呼和浩特", "银川",
            "西宁", "拉萨", "香港");

    /**
     * 岗位关键词 —— 用来判断用户填的岗位是不是「模型熟悉的常见方向」。
     * <p>
     * 不追求覆盖全：判断标准是「命中任一关键词就算常见」，
     * 像「火星殖民架构师」这种没人见过的岗位就该降到 MEDIUM 以下。
     */
    private static final List<String> POSITION_KEYWORDS = List.of(
            "java", "后端", "前端", "全栈", "客户端", "android", "ios", "移动端",
            "算法", "机器学习", "深度学习", "人工智能", "ai", "数据", "大数据", "数据分析",
            "测试", "运维", "devops", "安全", "架构", "技术经理", "技术专家",
            "产品", "运营", "设计", "ui", "ue", "项目经理", "解决方案",
            "c++", "go", "golang", "python", "php", "node", "rust", "嵌入式", "dba");

    private static final Map<String, Integer> CONFIDENCE_RANK = Map.of(
            "LOW", 0, "MEDIUM", 1, "HIGH", 2);
    private static final String[] CONFIDENCE_NAME = {"LOW", "MEDIUM", "HIGH"};

    private final AiServiceClient aiClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SalaryEstimateServiceImpl(AiServiceClient aiClient,
                                     AuditService auditService,
                                     ObjectMapper objectMapper,
                                     TransactionTemplate transactionTemplate) {
        this.aiClient = aiClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * ⚠️ 刻意<b>不加 {@code @Transactional}</b>：整段要调模型（十几秒到一分钟），
     * 包在事务里会一直占着数据库连接。落库那一步单独用 TransactionTemplate 保证原子。
     */
    @Override
    public SalaryEstimateVO estimate(SalaryEstimateDTO dto) {
        Long userId = SecurityUtils.getUserId();
        String requestKey = fingerprint(dto);

        // ① 先看缓存 —— 同参数 7 天内不重复调模型
        SalaryEstimate cached = findFresh(userId, requestKey);
        if (cached != null) {
            log.info("薪酬估算命中缓存 userId={} city={} position={} years={}",
                    userId, dto.getCity(), dto.getPosition(), dto.getYearsOfExperience());
            return toVO(cached, true);
        }

        // ② 调模型（不占数据库事务）
        SalaryEstimateData data = aiClient.estimateSalary(new SalaryEstimateRequest(
                String.valueOf(userId),
                dto.getCity().trim(),
                dto.getPosition().trim(),
                dto.getYearsOfExperience(),
                trimToNull(dto.getEducation()),
                trimToNull(dto.getCompanyType()),
                cleanSkills(dto.getSkills()),
                dto.getCurrentAnnualSalary()));

        // ③ 结果自检：三个分位必须齐、必须递增；月数必须在合理区间
        double[] monthly = ascendingOrThrow("月薪", data.monthlyP50(), data.monthlyP75(), data.monthlyP90());
        double[] annual = ascendingOrThrow("年包", data.annualP50(), data.annualP75(), data.annualP90());
        Integer months = clampMonths(data.monthsPerYear());

        // ④ 置信度按规则钳制 —— 不直接采信模型自评
        String confidence = clampConfidence(data.confidence(), dto);

        SalaryEstimate entity = new SalaryEstimate()
                .setUserId(userId)
                .setRequestKey(requestKey)
                .setCity(dto.getCity().trim())
                .setPosition(dto.getPosition().trim())
                .setYearsOfExperience(dto.getYearsOfExperience())
                .setEducation(trimToNull(dto.getEducation()))
                .setCompanyType(trimToNull(dto.getCompanyType()))
                .setSkillsJson(toJson(cleanSkills(dto.getSkills())))
                .setCurrentAnnualSalary(dto.getCurrentAnnualSalary())
                .setMonthlyP50(toDecimal(monthly[0]))
                .setMonthlyP75(toDecimal(monthly[1]))
                .setMonthlyP90(toDecimal(monthly[2]))
                .setAnnualP50(toDecimal(annual[0]))
                .setAnnualP75(toDecimal(annual[1]))
                .setAnnualP90(toDecimal(annual[2]))
                .setMonthsPerYear(months)
                .setConfidence(confidence)
                .setBasisJson(toJson(data.basis()))
                .setFactorsJson(toJson(data.factors()))
                // ★ 固定文案，不用模型给的
                .setCaveat(CAVEAT)
                .setCreatedAt(LocalDateTime.now());

        // ⑤ 落库（单表单行，但仍走事务模板 —— 与项目里「AI 之后落库」的写法保持一致）
        transactionTemplate.executeWithoutResult(tx -> save(entity));

        log.info("薪酬估算完成 userId={} city={} position={} years={} confidence={}（模型给 {}）"
                        + " 月薪 {}/{}/{}",
                userId, dto.getCity(), dto.getPosition(), dto.getYearsOfExperience(),
                confidence, data.confidence(), monthly[0], monthly[1], monthly[2]);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("city", entity.getCity());
        detail.put("position", entity.getPosition());
        detail.put("yearsOfExperience", entity.getYearsOfExperience());
        detail.put("confidence", confidence);
        auditService.recordSuccess(AuditConstants.ACTION_SALARY_ESTIMATE,
                AuditConstants.RESOURCE_SALARY_ESTIMATE, String.valueOf(entity.getId()), detail);

        return toVO(entity, false);
    }

    @Override
    public PageResultVO<SalaryEstimateVO> history(long page, long pageSize) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        Page<SalaryEstimate> result = page(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<SalaryEstimate>()
                        .eq(SalaryEstimate::getUserId, SecurityUtils.getUserId())
                        .orderByDesc(SalaryEstimate::getCreatedAt)
                        .orderByDesc(SalaryEstimate::getId));

        List<SalaryEstimateVO> records = result.getRecords().stream()
                .map(e -> toVO(e, false))
                .toList();
        return PageResultVO.of(result, records);
    }

    /* ---------- 缓存 ---------- */

    /** 7 天内、同参数的最近一条。命中就复用它，不新增行 */
    private SalaryEstimate findFresh(Long userId, String requestKey) {
        return getOne(new LambdaQueryWrapper<SalaryEstimate>()
                .eq(SalaryEstimate::getUserId, userId)
                .eq(SalaryEstimate::getRequestKey, requestKey)
                .ge(SalaryEstimate::getCreatedAt, LocalDateTime.now().minusDays(CACHE_DAYS))
                .orderByDesc(SalaryEstimate::getId)
                .last("LIMIT 1"));
    }

    /**
     * 参数指纹。
     * <p>
     * ⚠️ 技能要先<b>排序</b>再拼 —— 用户把「Java, MySQL」写成「MySQL, Java」时，
     * 语义完全一样，不该因为顺序不同就重新调一次模型。
     * <p>
     * ⚠️ 凡是<b>会传给模型</b>的字段都必须进指纹（包括当前年包）——
     * 少放一个，用户改了它却命中旧缓存，返回的就是按旧输入算出来的数，
     * 而且看不出哪里不对。
     */
    private String fingerprint(SalaryEstimateDTO dto) {
        String raw = String.join("|",
                normalize(dto.getCity()),
                normalize(dto.getPosition()),
                String.valueOf(dto.getYearsOfExperience()),
                normalize(dto.getEducation()),
                normalize(dto.getCompanyType()),
                String.join(",", cleanSkills(dto.getSkills()).stream()
                        .map(s -> s.toLowerCase(Locale.ROOT)).sorted().toList()),
                dto.getCurrentAnnualSalary() == null
                        ? "" : dto.getCurrentAnnualSalary().stripTrailingZeros().toPlainString());
        return sha256(raw);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /* ---------- 置信度 ---------- */

    /**
     * 把模型自评的置信度按「输入是否常见」钳一次。
     * <p>
     * 模型对什么组合都敢说 HIGH —— 它没有「我不确定」的动机。
     * 这里用几条可解释的规则封顶：
     * <ul>
     *   <li>岗位不是常见方向（没命中任何关键词）→ 最多 MEDIUM；</li>
     *   <li>城市不在常见城市表 → 最多 MEDIUM；</li>
     *   <li>年限为 0（应届）或 &gt; 15（高阶岗）→ 最多 MEDIUM，这两端的样本最少；</li>
     *   <li>岗位和城市<b>都</b>不认识 → 直接 LOW。</li>
     * </ul>
     * 最终取 {@code min(模型自评, 规则上限)}：模型说 LOW 就还是 LOW，
     * 规则只负责把过高的往下压，不会替模型往上抬。
     */
    private String clampConfidence(String modelConfidence, SalaryEstimateDTO dto) {
        int model = CONFIDENCE_RANK.getOrDefault(
                modelConfidence == null ? "" : modelConfidence.trim().toUpperCase(Locale.ROOT), 0);

        boolean knownPosition = isKnownPosition(dto.getPosition());
        boolean knownCity = isKnownCity(dto.getCity());
        int ceiling = CONFIDENCE_RANK.get("HIGH");

        if (!knownPosition) {
            ceiling = Math.min(ceiling, CONFIDENCE_RANK.get("MEDIUM"));
        }
        if (!knownCity) {
            ceiling = Math.min(ceiling, CONFIDENCE_RANK.get("MEDIUM"));
        }
        Integer years = dto.getYearsOfExperience();
        if (years != null && (years == 0 || years > 15)) {
            ceiling = Math.min(ceiling, CONFIDENCE_RANK.get("MEDIUM"));
        }
        if (!knownPosition && !knownCity) {
            ceiling = CONFIDENCE_RANK.get("LOW");
        }

        return CONFIDENCE_NAME[Math.min(model, ceiling)];
    }

    private boolean isKnownPosition(String position) {
        if (!StringUtils.hasText(position)) {
            return false;
        }
        String lower = position.toLowerCase(Locale.ROOT);
        return POSITION_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private boolean isKnownCity(String city) {
        return StringUtils.hasText(city) && COMMON_CITIES.contains(city.trim());
    }

    /* ---------- 结果自检 ---------- */

    /**
     * 三个分位必须齐全且递增。
     * <p>
     * 模型偶尔会把顺序写反（P90 给成最低）。这里不报错 —— 用户看到一条报错
     * 比看到一个顺序修正过的区间更糟 —— 但要<b>排序并留 WARN</b>，
     * 因为这说明 prompt 或模型在漂。
     */
    private double[] ascendingOrThrow(String label, Double p50, Double p75, Double p90) {
        if (p50 == null || p75 == null || p90 == null) {
            throw new BizException(ErrorCode.AI_SERVICE_ERROR, label + "分位不完整，请稍后重试");
        }
        double[] values = {p50, p75, p90};
        boolean ascending = values[0] <= values[1] && values[1] <= values[2];
        if (!ascending) {
            log.warn("薪酬估算的{}分位不是递增的（{}/{}/{}），已按升序修正", label, p50, p75, p90);
            java.util.Arrays.sort(values);
        }
        return values;
    }

    private Integer clampMonths(Integer months) {
        if (months == null) {
            return null;
        }
        if (months < MIN_MONTHS || months > MAX_MONTHS) {
            log.warn("薪酬估算返回的月数不合理（{}），按 null 处理", months);
            return null;
        }
        return months;
    }

    /* ---------- 转换 ---------- */

    private SalaryEstimateVO toVO(SalaryEstimate entity, boolean cached) {
        SalaryEstimateVO vo = new SalaryEstimateVO();
        vo.setId(String.valueOf(entity.getId()));
        vo.setCity(entity.getCity());
        vo.setPosition(entity.getPosition());
        vo.setYearsOfExperience(entity.getYearsOfExperience());
        vo.setEducation(entity.getEducation());
        vo.setCompanyType(entity.getCompanyType());
        vo.setSkills(parseStringList(entity.getSkillsJson()));
        vo.setCurrentAnnualSalary(entity.getCurrentAnnualSalary());
        vo.setMonthlyP50(entity.getMonthlyP50());
        vo.setMonthlyP75(entity.getMonthlyP75());
        vo.setMonthlyP90(entity.getMonthlyP90());
        vo.setAnnualP50(entity.getAnnualP50());
        vo.setAnnualP75(entity.getAnnualP75());
        vo.setAnnualP90(entity.getAnnualP90());
        vo.setMonthsPerYear(entity.getMonthsPerYear());
        vo.setConfidence(entity.getConfidence());
        vo.setBasis(parseStringList(entity.getBasisJson()));
        vo.setFactors(parseStringList(entity.getFactorsJson()));
        vo.setCaveat(entity.getCaveat());
        vo.setCached(cached);
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** 技能：去空、去重、保持用户输入顺序（模型看到的顺序也更自然） */
    private List<String> cleanSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String skill : skills) {
            if (!StringUtils.hasText(skill)) {
                continue;
            }
            String trimmed = skill.trim();
            if (!out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("JSON 数组解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败，按空处理：{}", e.getMessage());
            return null;
        }
    }

    /** 用 valueOf 而不是 new BigDecimal(double) —— 后者会把 25.5 变成一长串二进制误差 */
    private BigDecimal toDecimal(double value) {
        return BigDecimal.valueOf(value);
    }
}
