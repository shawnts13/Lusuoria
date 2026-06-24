package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 项目订单 - 系统核心表
 * 项目编号格式：品牌方-月份-红人ID-序号，如 TEMU-202604-bigdogtech-001
 */
@Entity
@Table(name = "project_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectOrder extends BaseEntity {

    // ===== 基本信息 =====
    @Column(name = "internal_project_no", nullable = false, unique = true, length = 100)
    private String internalProjectNo;

    @Column(name = "client_order_no", length = 100)
    private String clientOrderNo;

    @Column(name = "project_month", nullable = false, length = 6)
    private String projectMonth;          // 格式 202604

    @Enumerated(EnumType.STRING)
    @Column(name = "project_type", nullable = false, length = 30)
    private ProjectType projectType;

    @Column(name = "cooperation_content", length = 200)
    private String cooperationContent;

    @Column(name = "is_own_resource")
    private Boolean isOwnResource = false; // 自带资源/供应商项目（影响提成比例）

    // ===== 关联 =====
    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Column(name = "influencer_id", insertable = false, updatable = false)
    private Long influencerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_id")
    private Influencer influencer;

    @Column(name = "project_manager_id", insertable = false, updatable = false)
    private Long projectManagerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_manager_id")
    private Employee projectManager;

    // ===== 收入 =====
    /** 客户合作价格（美金），原"客户单价"，现参与利润计算 */
    @Column(name = "client_price", precision = 15, scale = 2)
    private BigDecimal clientPrice;

    @Column(name = "exchange_rate", precision = 10, scale = 4)
    private BigDecimal exchangeRate;

    @Column(name = "rmb_revenue", precision = 15, scale = 2)
    private BigDecimal rmbRevenue;        // 自动计算（公司利润（人民币））

    // ===== 成本 =====
    @Column(name = "influencer_cost", precision = 15, scale = 2)
    private BigDecimal influencerCost;    // 自动计算（中国红人）或直填（海外红人）

    @Column(name = "other_external_cost", precision = 15, scale = 2)
    private BigDecimal otherExternalCost;

    @Column(name = "internal_execution_cost", precision = 15, scale = 2)
    private BigDecimal internalExecutionCost;

    // ===== 自动计算利润 =====
    @Column(name = "gross_profit", precision = 15, scale = 2)
    private BigDecimal grossProfit;

    @Column(name = "distributable_profit", precision = 15, scale = 2)
    private BigDecimal distributableProfit;

    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;    // 负责人提成比例

    @Column(name = "commission_amount", precision = 15, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "company_net_profit", precision = 15, scale = 2)
    private BigDecimal companyNetProfit;

    // ===== 甲方状态 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "client_status", length = 30)
    private ClientStatus clientStatus = ClientStatus.PENDING_SUBMIT;

    @Column(name = "contract_signed")
    private Boolean contractSigned = false;

    @Temporal(TemporalType.DATE)
    @Column(name = "expected_receipt_date")
    private Date expectedReceiptDate;

    @Temporal(TemporalType.DATE)
    @Column(name = "actual_receipt_date")
    private Date actualReceiptDate;

    @Column(name = "received_amount", precision = 15, scale = 2)
    private BigDecimal receivedAmount;

    // ===== 内部状态 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "internal_status", length = 30)
    private InternalSettlementStatus internalStatus = InternalSettlementStatus.PENDING_CALC;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
