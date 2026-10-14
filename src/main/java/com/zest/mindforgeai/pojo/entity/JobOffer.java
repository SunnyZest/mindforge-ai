package com.zest.mindforgeai.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收到的 Offer（用于多 Offer 对比决策）。
 * <p>
 * ★ <b>为什么五个维度都由用户打分，而不是按薪资自动算分</b>：
 * 自动按「最高薪归一化」会让最高薪那家永远满分，而用户对薪资的满意度是非线性的
 * （30 万可能已经够了，35 万不会让他更满意多少）。打分表的价值就在用户的主观权衡。
 * {@link #salaryAnnual} 只用于展示参考，不参与计算。
 *
 * @author crx
 * @since 2026-10-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("job_offer")
@Schema(description = "收到的 Offer")
public class JobOffer implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属用户 id，逻辑关联 user.id")
    private Long userId;

    @Schema(description = "关联的职位 id（可选）—— 从投递看板导入时带上，便于回溯 JD")
    private Long jobId;

    @Schema(description = "公司名")
    private String companyName;

    @Schema(description = "岗位名，可空")
    private String positionName;

    @Schema(description = "年薪（万元），仅展示参考，不参与算分")
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

    @Schema(description = "各维度打分的理由（JSON：{salary: \"...\", tech: \"...\"}），可空")
    private String dimensionNotesJson;

    @Schema(description = "状态：PENDING 考虑中 / ACCEPTED 已接受 / DECLINED 已拒绝")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改时间")
    private LocalDateTime updatedAt;

    @Schema(description = "逻辑删除：0 未删除 / 1 已删除")
    private Integer deleted;
}
