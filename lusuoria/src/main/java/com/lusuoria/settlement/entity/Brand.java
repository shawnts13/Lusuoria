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
     * 阈值分档结算专用：金额阈值，单位是本品牌方的结算币种（settlementCurrency）。只有
     * paymentCycleType = COST_THRESHOLD 时才有意义。
     *
     * 2026-08 起口径变更：需要invoice的品牌方（requiresInvoiceUpload()==true），一次结款
     * 只能对应一个"红人需求管理"里的需求（一张invoice），所以这里比较的是"单个需求实际可结款
     * 成本"——即该需求下所有已发布(未结算)/已加入客户未结算列表/客户已结算 记录的红人视频制作
     * 与发布成本之和，不再是单笔视频的成本，见 InfluencerPaymentService.computeCycleInfo/
     * RequirementInfo.payableCost。**不是** InfluencerRequirement.totalInfluencerCost（那是
     * 需求创建时按单价×数量算好的计划总成本）——2026-08 首版曾经直接用那个字段，但发现"折损"
     * 状态的条目不会真正付款，用计划总成本会把折损部分也算进阈值，导致分档/预计付款日算多，
     * 随后改成从实际记录聚合、天然排除折损。不需要invoice的 COST_THRESHOLD 品牌方（目前没有
     * 配置这种的，但字段允许）仍按老口径——单笔"红人视频制作与发布成本"。
     * 注意：ProgressReminderService 的"待处理提醒"批次目前还是旧的单笔口径，尚未跟进这次调整，
     * 后续会单独处理，读这两处代码时不要假设它们口径一致。
     */
    @Column(name = "cost_threshold_amount", precision = 15, scale = 2)
    private java.math.BigDecimal costThresholdAmount;

    /** 阈值分档结算专用：成本 <= costThresholdAmount 时，多少天内结款（成本口径见 costThresholdAmount 注释） */
    @Column(name = "days_within_threshold")
    private Integer daysWithinThreshold;

    /** 阈值分档结算专用：成本 > costThresholdAmount 时，多少天内结款（成本口径见 costThresholdAmount 注释） */
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

    /** 合同签订周期（2026-07 起配套"红人需求管理"合同上传功能落地使用） */
    @Enumerated(EnumType.STRING)
    @Column(name = "contract_cycle_type")
    private ContractCycleType contractCycleType;

    /**
     * 是否涉及"客户方的项目订单"（2026-08 新增）。null/true 都按"涉及"处理，只有显式设成 false
     * 才是"不涉及"，跟 requiresInvoiceUpload() 的 null 安全默认思路一致——新品牌方默认走更保守
     * 的"涉及"分支。涉及时，红人合作跟踪记录的视频项目进度流转到"已加入客户未结算列表"/
     * "客户已结算"这两个状态要求已经填写 CollaborationTracking.clientOrderId，
     * 见 CollaborationTrackingService。
     */
    @Column(name = "involves_client_order_id")
    private Boolean involvesClientOrderId;

    /**
     * 是否涉及"客户方付款批次"（2026-08 新增）。null/true 都按"涉及"处理，只有显式设成 false
     * 才是"不涉及"。涉及时，红人合作跟踪记录的视频项目进度流转到"客户已结算"要求已经填写
     * CollaborationTracking.clientPaymentBatch，见 CollaborationTrackingService。
     */
    @Column(name = "involves_client_payment_batch")
    private Boolean involvesClientPaymentBatch;

    /**
     * "红人结款"上传公对公发票流程默认值（2026-08 新增）。跟上面几个 requires.../involves...
     * 字段方向相反——这个默认是"不涉及"（null/false 都按"不涉及"处理，只有显式设成 true 才是
     * "涉及"），主要给 TEMU海外、ATOMS 这类品牌方下没有配团队的情况用（没有团队可以单独配置，
     * 只能在品牌方这一级兜底）。品牌方下有团队时，每个团队可以在"管理团队"单独覆盖
     * （{@link InfluencerTeam#involvesCorporateInvoice}），团队有显式配置时以团队为准，
     * 团队没配置（null）时才落回这个品牌方默认值，见
     * {@link InfluencerTeam#involvesCorporateInvoice(Brand, InfluencerTeam)}。
     */
    @Column(name = "default_involves_corporate_invoice")
    private Boolean defaultInvolvesCorporateInvoice;

    /** null 按"需要 invoice"处理，只有显式设成 false 才是"不涉及"——统一用这个方法判断，不要在别处重复 !Boolean.FALSE.equals(...) */
    public boolean requiresInvoiceUpload() {
        return !Boolean.FALSE.equals(requiresInvoice);
    }

    /** null 按"涉及"处理，只有显式设成 false 才是"不涉及"——统一用这个方法判断，不要在别处重复 !Boolean.FALSE.equals(...) */
    public boolean requiresClientOrderId() {
        return !Boolean.FALSE.equals(involvesClientOrderId);
    }

    /** null 按"涉及"处理，只有显式设成 false 才是"不涉及"——统一用这个方法判断，不要在别处重复 !Boolean.FALSE.equals(...) */
    public boolean requiresClientPaymentBatch() {
        return !Boolean.FALSE.equals(involvesClientPaymentBatch);
    }

    /**
     * 合同是否按"每次需求签一次"（true）还是"一年签一次"（false）。null 按"每次需求签一次"
     * 处理（还没配置的品牌方默认走更宽松、不需要额外去红人管理跳转的分支），跟
     * requiresInvoiceUpload() 的 null 安全默认思路一致——统一用这个方法判断，不要在别处
     * 重复写 contractCycleType != ContractCycleType.ANNUAL。
     */
    public boolean isPerRequirementContract() {
        return contractCycleType != ContractCycleType.ANNUAL;
    }
}