package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.PaymentCycleType;
import javax.persistence.*;
import lombok.*;

@Entity
@Table(name = "brands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Brand extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true)
    private String name;                 // 品牌方名称，如 TEMU、Atoms

    @Column(name = "country_market")
    private String countryMarket;        // 国家/市场

    @Column(name = "cooperation_type")
    private String cooperationType;      // 合作类型

    @Column(name = "contact_person")
    private String contactPerson;        // 联系人

    @Column(name = "settlement_currency", length = 10)
    private String settlementCurrency;   // 结算币种 USD/RMB

    /**
     * 付款周期类型（2026-07 起，原来的自由文本"付款周期"字段改成结构化配置，
     * 为红人结款功能建设铺路）。默认空，表示还没配置。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_cycle_type")
    private PaymentCycleType paymentCycleType;

    /**
     * 阈值分档结算专用：单笔"红人视频制作与发布成本"金额阈值，单位是本品牌方的结算币种
     * （settlementCurrency）。只有 paymentCycleType = COST_THRESHOLD 时才有意义。
     */
    @Column(name = "cost_threshold_amount", precision = 15, scale = 2)
    private java.math.BigDecimal costThresholdAmount;

    /** 阈值分档结算专用：单笔红人成本 <= costThresholdAmount 时，多少天内结款 */
    @Column(name = "days_within_threshold")
    private Integer daysWithinThreshold;

    /** 阈值分档结算专用：单笔红人成本 > costThresholdAmount 时，多少天内结款 */
    @Column(name = "days_above_threshold")
    private Integer daysAboveThreshold;

    /** 月结专用：月底对账日后多少天内结款。只有 paymentCycleType = MONTH_END 时才有意义 */
    @Column(name = "days_after_month_end")
    private Integer daysAfterMonthEnd;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;                // 备注
}