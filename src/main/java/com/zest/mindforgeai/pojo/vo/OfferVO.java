package com.zest.mindforgeai.pojo.vo;

import com.zest.mindforgeai.pojo.entity.JobOffer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Offer 项（列表与详情共用）。
 *
 * @author crx
 * @since 2026-10-14
 */
@Schema(description = "Offer 项")
@Data
public class OfferVO {

    @Schema(description = "Offer id（字符串，避免 JS 精度丢失）")
    private String id;

    @Schema(description = "关联的职位 id，可空")
    private String jobId;

    @Schema(description = "公司名")
    private String companyName;

    @Schema(description = "岗位名")
    private String positionName;

    @Schema(description = "年薪（万元），仅展示参考")
    private BigDecimal salaryAnnual;

    @Schema(description = "薪资包评分 1-10")
    private Integer salaryScore;

    @Schema(description = "技术栈发展评分 1-10")
    private Integer techScore;

    @Schema(description = "平台背书评分 1-10")
    private Integer brandScore;

    @Schema(description = "加班强度评分 1-10（分越高越轻松）")
    private Integer wlbScore;

    @Schema(description = "通勤距离评分 1-10（分越高越方便）")
    private Integer commuteScore;

    @Schema(description = "各维度打分的理由（JSON 字符串，前端自行解析）")
    private String dimensionNotes;

    @Schema(description = "状态：PENDING / ACCEPTED / DECLINED")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;

    public static OfferVO of(JobOffer offer) {
        OfferVO vo = new OfferVO();
        vo.setId(offer.getId() == null ? null : String.valueOf(offer.getId()));
        vo.setJobId(offer.getJobId() == null ? null : String.valueOf(offer.getJobId()));
        vo.setCompanyName(offer.getCompanyName());
        vo.setPositionName(offer.getPositionName());
        vo.setSalaryAnnual(offer.getSalaryAnnual());
        vo.setSalaryScore(offer.getSalaryScore());
        vo.setTechScore(offer.getTechScore());
        vo.setBrandScore(offer.getBrandScore());
        vo.setWlbScore(offer.getWlbScore());
        vo.setCommuteScore(offer.getCommuteScore());
        vo.setDimensionNotes(offer.getDimensionNotesJson());
        vo.setStatus(offer.getStatus());
        vo.setCreatedAt(offer.getCreatedAt());
        vo.setUpdatedAt(offer.getUpdatedAt());
        return vo;
    }
}
