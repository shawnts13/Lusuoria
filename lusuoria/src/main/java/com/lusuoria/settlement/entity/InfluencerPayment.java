package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 红人结款：一条结款记录 = 一个"品牌方-红人团队-结算月份"批次，
 * 对应多条红人合作跟踪记录（见 CollaborationTracking.influencerPaymentId）。
 * 合作数量/应付金额 由勾选的红人合作跟踪记录带出来，创建/调整勾选后由后端算好存库，
 * 不接受前端直接传入覆盖。
 */
@Entity
@Table(name = "influencer_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerPayment extends BaseEntity {

    /** 结款单号，创建时立即生成，格式见 PaymentNoGenerator */
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

    /** 红人团队，可为空（品牌方下没有配团队的情况） */
    @Column(name = "team_id", insertable = false, updatable = false)
    private Long teamId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private InfluencerTeam team;

    /** 合作数量：勾选的红人合作跟踪条目数，只读，由后端算出来存库 */
    @Column(name = "cooperation_quantity")
    private Integer cooperationQuantity;

    /** 应付金额：勾选条目的红人视频制作与发布成本之和，只读，由后端算出来存库 */
    @Column(name = "payable_amount", precision = 15, scale = 2)
    private BigDecimal payableAmount;

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
