package com.zest.mindforgeai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zest.mindforgeai.common.constant.AuditConstants;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.query.SortSupport;
import com.zest.mindforgeai.mapper.JobOfferMapper;
import com.zest.mindforgeai.pojo.dto.OfferCompareDTO;
import com.zest.mindforgeai.pojo.dto.OfferCreateDTO;
import com.zest.mindforgeai.pojo.dto.OfferStatusDTO;
import com.zest.mindforgeai.pojo.dto.OfferUpdateDTO;
import com.zest.mindforgeai.pojo.entity.JobOffer;
import com.zest.mindforgeai.pojo.vo.JobDetailVO;
import com.zest.mindforgeai.pojo.vo.OfferCompareVO;
import com.zest.mindforgeai.pojo.vo.OfferVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.service.AuditService;
import com.zest.mindforgeai.service.JobService;
import com.zest.mindforgeai.service.OfferService;
import com.zest.mindforgeai.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Offer 对比实现。
 *
 * @author crx
 * @since 2026-10-14
 */
@Slf4j
@Service
public class OfferServiceImpl extends ServiceImpl<JobOfferMapper, JobOffer> implements OfferService {

    private static final long MAX_PAGE_SIZE = 100;

    /** 状态取值。PENDING 是默认值 —— 刚录入的 Offer 都是「还在考虑」 */
    private static final String STATUS_PENDING = "PENDING";
    private static final Set<String> STATUSES = Set.of(STATUS_PENDING, "ACCEPTED", "DECLINED");

    /** 一次最多对比几个 —— 超过 5 个横向表格在窄屏上就没法看了 */
    private static final int MAX_COMPARE = 5;
    private static final int MIN_COMPARE = 2;

    /** 分差小于这个值就认为「基本持平」（满分 10 分） */
    private static final double TIE_THRESHOLD = 0.3;

    /** 单个维度权重超过这个比例，就认为用户「明显更看重这一维」，建议里要点出来 */
    private static final double DOMINANT_WEIGHT = 0.35;

    /** 维度英文 key → 中文标签（生成建议文案用） */
    private static final Map<String, String> DIM_LABELS = Map.of(
            "salary", "薪资包",
            "tech", "技术栈发展",
            "brand", "平台背书",
            "wlb", "加班强度",
            "commute", "通勤距离");

    private static final Map<String, SFunction<JobOffer, ?>> SORTABLE = Map.of(
            "companyName", JobOffer::getCompanyName,
            "salaryAnnual", JobOffer::getSalaryAnnual,
            "createdAt", JobOffer::getCreatedAt,
            "updatedAt", JobOffer::getUpdatedAt);

    private final AuditService auditService;
    private final JobService jobService;

    public OfferServiceImpl(AuditService auditService, JobService jobService) {
        this.auditService = auditService;
        this.jobService = jobService;
    }

    @Override
    public PageResultVO<OfferVO> pageOffers(long page, long pageSize, String keyword, String status,
                                            String sortBy, String sortOrder) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        String safeKeyword = SortSupport.escapeLike(keyword);
        LambdaQueryWrapper<JobOffer> wrapper = new LambdaQueryWrapper<JobOffer>()
                .eq(JobOffer::getUserId, SecurityUtils.getUserId())
                .eq(StringUtils.hasText(status), JobOffer::getStatus, status)
                .and(StringUtils.hasText(safeKeyword), w -> w
                        .like(JobOffer::getCompanyName, safeKeyword).or()
                        .like(JobOffer::getPositionName, safeKeyword));

        SortSupport.applyTo(wrapper, sortBy, sortOrder, SORTABLE, JobOffer::getId, false);

        Page<JobOffer> result = page(new Page<>(safePage, safeSize), wrapper);
        return PageResultVO.of(result, result.getRecords().stream().map(OfferVO::of).toList());
    }

    @Override
    public OfferVO getDetail(Long id) {
        return OfferVO.of(requireOwned(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OfferVO create(OfferCreateDTO dto) {
        JobOffer offer = new JobOffer()
                .setUserId(SecurityUtils.getUserId())
                .setCompanyName(dto.getCompanyName().trim())
                .setPositionName(trimToNull(dto.getPositionName()))
                .setSalaryAnnual(dto.getSalaryAnnual())
                .setSalaryScore(dto.getSalaryScore())
                .setTechScore(dto.getTechScore())
                .setBrandScore(dto.getBrandScore())
                .setWlbScore(dto.getWlbScore())
                .setCommuteScore(dto.getCommuteScore())
                .setDimensionNotesJson(trimToNull(dto.getDimensionNotes()))
                .setStatus(STATUS_PENDING)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now())
                .setDeleted(0);

        // 从投递看板导入：带上公司名和岗位名，省得重复录入。
        // ⚠️ 刻意【不】自动带薪资：job.salary_offer 是自由文本（"30k*15"、"面议"），
        //    而这里的 salary_annual 是数值型，猜错单位比让用户自己填更糟。
        if (StringUtils.hasText(dto.getJobId())) {
            Long jobId = parseId(dto.getJobId(), "职位 id");
            JobDetailVO job = jobService.getDetail(jobId);   // 内部会校验归属
            offer.setJobId(jobId);
            if (!StringUtils.hasText(dto.getCompanyName()) && StringUtils.hasText(job.getCompanyName())) {
                offer.setCompanyName(job.getCompanyName());
            }
            if (offer.getPositionName() == null) {
                offer.setPositionName(job.getTitle());
            }
        }

        save(offer);

        auditService.recordSuccess(AuditConstants.ACTION_OFFER_CREATE,
                AuditConstants.RESOURCE_OFFER, String.valueOf(offer.getId()),
                Map.of("companyName", offer.getCompanyName()));

        log.info("新增 Offer id={} company={}", offer.getId(), offer.getCompanyName());
        return OfferVO.of(offer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OfferVO update(Long id, OfferUpdateDTO dto) {
        requireOwned(id);

        // 用 LambdaUpdateWrapper 而不是 updateById：后者忽略 null 字段，
        // 「把岗位名清空」这种操作就永远做不到（同简历在线编辑的取舍）。
        LambdaUpdateWrapper<JobOffer> update = new LambdaUpdateWrapper<JobOffer>()
                .eq(JobOffer::getId, id)
                .eq(JobOffer::getUserId, SecurityUtils.getUserId())
                .set(JobOffer::getUpdatedAt, LocalDateTime.now());

        if (StringUtils.hasText(dto.getCompanyName())) {
            update.set(JobOffer::getCompanyName, dto.getCompanyName().trim());
        }
        if (dto.getPositionName() != null) {
            update.set(JobOffer::getPositionName, trimToNull(dto.getPositionName()));
        }
        if (dto.getSalaryAnnual() != null) {
            update.set(JobOffer::getSalaryAnnual, dto.getSalaryAnnual());
        }
        if (dto.getSalaryScore() != null) {
            update.set(JobOffer::getSalaryScore, dto.getSalaryScore());
        }
        if (dto.getTechScore() != null) {
            update.set(JobOffer::getTechScore, dto.getTechScore());
        }
        if (dto.getBrandScore() != null) {
            update.set(JobOffer::getBrandScore, dto.getBrandScore());
        }
        if (dto.getWlbScore() != null) {
            update.set(JobOffer::getWlbScore, dto.getWlbScore());
        }
        if (dto.getCommuteScore() != null) {
            update.set(JobOffer::getCommuteScore, dto.getCommuteScore());
        }
        if (dto.getDimensionNotes() != null) {
            update.set(JobOffer::getDimensionNotesJson, trimToNull(dto.getDimensionNotes()));
        }

        update(update);
        return OfferVO.of(requireOwned(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        JobOffer offer = requireOwned(id);
        removeById(id);

        auditService.recordSuccess(AuditConstants.ACTION_OFFER_DELETE,
                AuditConstants.RESOURCE_OFFER, String.valueOf(id),
                Map.of("companyName", offer.getCompanyName()));

        log.info("删除 Offer id={} company={}", id, offer.getCompanyName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OfferVO updateStatus(Long id, OfferStatusDTO dto) {
        JobOffer offer = requireOwned(id);
        String status = dto.getStatus().trim().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能是 PENDING / ACCEPTED / DECLINED");
        }

        update(new LambdaUpdateWrapper<JobOffer>()
                .eq(JobOffer::getId, id)
                .eq(JobOffer::getUserId, SecurityUtils.getUserId())
                .set(JobOffer::getStatus, status)
                .set(JobOffer::getUpdatedAt, LocalDateTime.now()));

        log.info("Offer 状态变更 id={} {} → {}", id, offer.getStatus(), status);
        return OfferVO.of(requireOwned(id));
    }

    @Override
    public OfferCompareVO compare(OfferCompareDTO dto) {
        List<Long> ids = parseIds(dto.getOfferIds());
        if (ids.size() < MIN_COMPARE || ids.size() > MAX_COMPARE) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "一次可以对比 " + MIN_COMPARE + "~" + MAX_COMPARE + " 个 Offer");
        }

        // 逐个校验归属 —— 不是自己的直接 404（不区分「不存在」和「不是你的」）
        List<JobOffer> offers = new ArrayList<>(ids.size());
        for (Long id : ids) {
            offers.add(requireOwned(id));
        }

        Map<String, Double> weights = normalizeWeights(dto);

        // 算加权总分
        List<OfferCompareVO.Item> items = new ArrayList<>(offers.size());
        for (JobOffer offer : offers) {
            OfferCompareVO.Item item = new OfferCompareVO.Item();
            item.setOfferId(String.valueOf(offer.getId()));
            item.setCompanyName(offer.getCompanyName());
            item.setPositionName(offer.getPositionName());
            item.setSalaryAnnual(offer.getSalaryAnnual());
            item.setSalaryScore(offer.getSalaryScore());
            item.setTechScore(offer.getTechScore());
            item.setBrandScore(offer.getBrandScore());
            item.setWlbScore(offer.getWlbScore());
            item.setCommuteScore(offer.getCommuteScore());
            item.setStatus(offer.getStatus());
            item.setTotalScore(weightedScore(offer, weights));
            items.add(item);
        }

        // 按总分降序；同分时按公司名稳定排序，避免每次调用顺序抖动
        items.sort(Comparator.comparing(OfferCompareVO.Item::getTotalScore).reversed()
                .thenComparing(OfferCompareVO.Item::getCompanyName,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        assignRanks(items);
        Map<String, List<String>> bestByDimension = collectBestByDimension(offers);
        markBestDimensions(items, bestByDimension);

        OfferCompareVO vo = new OfferCompareVO();
        vo.setOfferIds(ids.stream().map(String::valueOf).toList());
        vo.setWeights(weights);
        vo.setOffers(items);
        vo.setBestByDimension(bestByDimension);
        vo.setRecommendation(buildRecommendation(items, weights, bestByDimension));

        log.info("Offer 对比：{} 个，冠军={}（{} 分）",
                items.size(), items.get(0).getCompanyName(), items.get(0).getTotalScore());
        return vo;
    }

    /* ---------- 对比算法 ---------- */

    /**
     * 权重归一化。
     * <p>
     * 规则：<b>全都不传 = 五维等权</b>；只要传了任意一个，未传的那些就按 0 算 ——
     * 用户传「薪资 50、技术 30」是在明确表达「我现在只看这两项」，
     * 这时候还给他平均分配权重才是违背意图。
     */
    private Map<String, Double> normalizeWeights(OfferCompareDTO dto) {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("salary", dto.getSalaryWeight());
        raw.put("tech", dto.getTechWeight());
        raw.put("brand", dto.getBrandWeight());
        raw.put("wlb", dto.getWlbWeight());
        raw.put("commute", dto.getCommuteWeight());

        boolean noneProvided = raw.values().stream().allMatch(Objects::isNull);
        Map<String, Double> weights = new LinkedHashMap<>();
        if (noneProvided) {
            raw.keySet().forEach(key -> weights.put(key, 1.0));
        } else {
            raw.forEach((key, value) -> weights.put(key, value == null ? 0.0 : value));
        }

        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "权重不能全为 0");
        }
        weights.replaceAll((key, value) -> value / sum);
        return weights;
    }

    /** 加权总分：Σ(维度分 × 权重)，保留两位小数 */
    private double weightedScore(JobOffer offer, Map<String, Double> weights) {
        double score = 0;
        score += nz(offer.getSalaryScore()) * weights.get("salary");
        score += nz(offer.getTechScore()) * weights.get("tech");
        score += nz(offer.getBrandScore()) * weights.get("brand");
        score += nz(offer.getWlbScore()) * weights.get("wlb");
        score += nz(offer.getCommuteScore()) * weights.get("commute");
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 排名：同分并列（第 1、1、3 这种），避免浮点误差造成「实际同分却分出先后」 */
    private void assignRanks(List<OfferCompareVO.Item> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0 && Objects.equals(sorted.get(i).getTotalScore(), sorted.get(i - 1).getTotalScore())) {
                sorted.get(i).setRank(sorted.get(i - 1).getRank());
            } else {
                sorted.get(i).setRank(i + 1);
            }
        }
    }

    /** 每个维度得分最高的 Offer（并列时都收进来） */
    private Map<String, List<String>> collectBestByDimension(List<JobOffer> offers) {
        Map<String, List<String>> best = new LinkedHashMap<>();
        best.put("salary", bestOf(offers, JobOffer::getSalaryScore));
        best.put("tech", bestOf(offers, JobOffer::getTechScore));
        best.put("brand", bestOf(offers, JobOffer::getBrandScore));
        best.put("wlb", bestOf(offers, JobOffer::getWlbScore));
        best.put("commute", bestOf(offers, JobOffer::getCommuteScore));
        return best;
    }

    private List<String> bestOf(List<JobOffer> offers, SFunction<JobOffer, Integer> getter) {
        int max = offers.stream().mapToInt(o -> nz(getter.apply(o))).max().orElse(0);
        return offers.stream()
                .filter(o -> nz(getter.apply(o)) == max)
                .map(o -> String.valueOf(o.getId()))
                .toList();
    }

    private void markBestDimensions(List<OfferCompareVO.Item> items,
                                    Map<String, List<String>> bestByDimension) {
        for (OfferCompareVO.Item item : items) {
            List<String> dims = new ArrayList<>();
            bestByDimension.forEach((dim, bestIds) -> {
                if (bestIds.contains(item.getOfferId())) {
                    dims.add(dim);
                }
            });
            item.setBestDimensions(dims);
        }
    }

    /**
     * 生成一句话建议。
     * <p>
     * 刻意不做「替你决定」的口气 —— 分数是用户自己打的分、权重是他自己设的，
     * 这里只负责把「按你自己的标准，结果是什么」讲清楚，外加一条容易被忽略的提醒。
     */
    private String buildRecommendation(List<OfferCompareVO.Item> sorted,
                                       Map<String, Double> weights,
                                       Map<String, List<String>> bestByDimension) {
        if (sorted.isEmpty()) {
            return "";
        }
        OfferCompareVO.Item top = sorted.get(0);
        StringBuilder sb = new StringBuilder();

        boolean tie = sorted.size() > 1
                && top.getTotalScore() - sorted.get(1).getTotalScore() < TIE_THRESHOLD;

        if (tie && top.getTotalScore().equals(sorted.get(1).getTotalScore())) {
            sb.append(String.format("%s 和 %s 总分相同（%.2f）—— 按你的权重算完全打平，",
                    top.getCompanyName(), sorted.get(1).getCompanyName(), top.getTotalScore()));
            sb.append("建议回到「哪一家你更愿意每天去上班」这种算不进表里的问题上做判断。");
        } else if (tie) {
            sb.append(String.format("前两名很接近（%s %.2f 分 vs %s %.2f 分）—— ",
                    top.getCompanyName(), top.getTotalScore(),
                    sorted.get(1).getCompanyName(), sorted.get(1).getTotalScore()));
            sb.append("差距在你的判断误差内，可以看哪家的「短板」你更能接受。");
        } else {
            sb.append(String.format("按你设的权重，%s（%.2f 分）更合适",
                    top.getCompanyName(), top.getTotalScore()));
            List<String> leadDims = top.getBestDimensions();
            if (!leadDims.isEmpty()) {
                sb.append("：它在")
                        .append(leadDims.stream().map(DIM_LABELS::get).reduce((a, b) -> a + "、" + b).orElse(""))
                        .append("上是最高的");
            }
            sb.append("。");
        }

        // ★ 容易被忽略的提醒：权重最重的那一维，冠军却不是最优 —— 值得让他再想想
        String dominantDim = weights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() >= DOMINANT_WEIGHT)
                .map(Map.Entry::getKey)
                .orElse(null);
        if (dominantDim != null && !bestByDimension.getOrDefault(dominantDim, List.of()).contains(top.getOfferId())) {
            List<String> winners = bestByDimension.getOrDefault(dominantDim, List.of());
            String winnerNames = sorted.stream()
                    .filter(i -> winners.contains(i.getOfferId()))
                    .map(OfferCompareVO.Item::getCompanyName)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            sb.append(String.format(" ⚠️ 你给「%s」的权重最重（%.0f%%），但这一维最优的是 %s —— 如果这点对你很重要，值得再权衡一下。",
                    DIM_LABELS.get(dominantDim), weights.get(dominantDim) * 100, winnerNames));
        }

        return sb.toString();
    }

    /* ---------- 内部工具 ---------- */

    private JobOffer requireOwned(Long id) {
        JobOffer offer = getById(id);
        if (offer == null || !offer.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.OFFER_NOT_FOUND);
        }
        return offer;
    }

    /** 解析并去重 id 列表（前端传字符串，避免 JS 精度丢失） */
    private List<Long> parseIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少选择 2 个 Offer 才能对比");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (String raw : rawIds) {
            unique.add(parseId(raw, "Offer id"));
        }
        return new ArrayList<>(unique);
    }

    private Long parseId(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "不能为空");
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "格式不正确：" + value);
        }
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
