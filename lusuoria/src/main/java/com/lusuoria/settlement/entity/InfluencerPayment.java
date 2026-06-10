package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

@Entity
@Table(name = "influencer_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerPayment extends BaseEntity {

    @Column(name = "payment_no", unique = true, length = 50)
    private String paymentNo;

    @Column(name = "settlement_month", nullable = false, length = 6)
    private String settlementMonth;

    @Column(name = "influencer_id", insertable = false, updatable = false)
    private Long influencerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_id", nullable = false)
    private Influencer influencer;

    @Column(name = "project_order_id", insertable = false, updatable = false)
    private Long projectOrderId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_order_id")
    private ProjectOrder projectOrder;

    @Column(name = "cooperation_content", length = 200)
    private String cooperationContent;

    @Column(name = "cooperation_quantity")
    private Integer cooperationQuantity;

    @Column(name = "influencer_unit_price", precision = 15, scale = 2)
    private BigDecimal influencerUnitPrice;

    @Column(name = "payable_amount", precision = 15, scale = 2)
    private BigDecimal payableAmount;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "exchange_rate", precision = 10, scale = 4)
    private BigDecimal exchangeRate;

    @Column(name = "rmb_amount", precision = 15, scale = 2)
    private BigDecimal rmbAmount;

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
    private InfluencerPaymentStatus paymentStatus = InfluencerPaymentStatus.PENDING_RECONCILE;

    @Column(name = "paid_amount", precision = 15, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
