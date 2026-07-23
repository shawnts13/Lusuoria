package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.ContractCycleType;
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

    /**
     * 是否需要 invoice（2026-07 起，替代原来打算写在业务代码里的"TEMU中国"硬编码判断）。
     * null/true 都按"需要"处理，只有显式设成 false 才是"不涉及 invoice 上传"，见
     * {@link #requiresInvoiceUpload()}——新品牌方不用今天就必须配置这个开关，默认走更安全的
     * "需要"分支。
     */
    @Column(name = "requires_invoice")
    private Boolean requiresInvoice;

    /** 合同签订周期（目前没有配套的合同上传功能，先落地配置项供以后使用） */
    @Enumerated(EnumType.STRING)
    @Column(name = "contract_cycle_type")
    private ContractCycleType contractCycleType;

    /** null 按"需要 invoice"处理，只有显式设成 false 才是"不涉及"——统一用这个方法判断，不要在别处重复 !Boolean.FALSE.equals(...) */
    public boolean requiresInvoiceUpload() {
        return !Boolean.FALSE.equals(requiresInvoice);
    }
}