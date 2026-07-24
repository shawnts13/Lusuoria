package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 红人结款：一条结款记录 = 一个"品牌方-结算月份"批次，可以跨多个红人团队合并结算
 * （2026-07 起支持），对应多条红人合作跟踪记录（见 CollaborationTracking.influencerPaymentId）。
 * 合作数量/应付金额 由勾选的红人合作跟踪记录带出来，创建/调整勾选后由后端算好存库，
 * 不接受前端直接传入覆盖。
 *
 * "涉及哪些团队"不再是这个实体自己的固定列，改成一张单独的关联表
 * InfluencerPaymentTeam 记录创建时选定的范围（可能包含"不选团队"）。
 */
@Entity
@Table(name = "influencer_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerPayment extends BaseEntity {

    /** 结款单号，创建时立即生成，格式见 PaymentNoGenerator（不再包含团队，因为可能涉及多个团队） */
    @Column(name = "payment_no", unique = true, length = 100)
    private String paymentNo;

    @Column(name = "settlement_month", nullable = false, length = 6)
    private String settlementMonth;

    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /**
     * 这条结款记录创建时选定的团队范围（见 InfluencerPaymentTeam），可能包含 null
     * （代表"不选团队"也在范围内）。瞬态字段，不落库，由 Controller/Service 批量查出来再赋值，
     * 供列表页展示"红人团队"列（多个团队名拼接）、导出用。
     */
    @Transient
    private List<Long> teamIds;

    /** 合作数量：勾选的红人合作跟踪条目数，只读，由后端算出来存库 */
    @Column(name = "cooperation_quantity")
    private Integer cooperationQuantity;

    /** 应付金额：勾选条目的红人视频制作与发布成本之和，只读，由后端算出来存库 */
    @Column(name = "payable_amount", precision = 15, scale = 2)
    private BigDecimal payableAmount;

    /**
     * 涉及的内部需求编号：勾选条目里出现过的 internalRequirementNo 去重后换行拼接
     * （一次结款可能涉及多个需求，所以是多值字段，沿用 MultiValueUtil 那一套"换行分隔存一列"
     * 的约定），只读，由后端在 create()/update() 时算出来存库。没关联任何需求的条目不计入。
     */
    @Column(name = "involved_requirement_nos", columnDefinition = "TEXT")
    private String involvedRequirementNos;

    /** 币种，目前固定 USD（红人视频合作都是美元结算） */
    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "exchange_rate", precision = 10, scale = 4)
    private BigDecimal exchangeRate;

    @Column(name = "rmb_amount", precision = 15, scale = 2)
    private BigDecimal rmbAmount;

    /** 对账日期，可选 */
    @Temporal(TemporalType.DATE)
    @Column(name = "reconcile_date")
    private Date reconcileDate;

    @Temporal(TemporalType.DATE)
    @Column(name = "expected_payment_date")
    private Date expectedPaymentDate;

    @Temporal(TemporalType.DATE)
    @Column(name = "actual_payment_date")
    private Date actualPaymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 30)
    private InfluencerPaymentStatus paymentStatus = InfluencerPaymentStatus.PENDING;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
