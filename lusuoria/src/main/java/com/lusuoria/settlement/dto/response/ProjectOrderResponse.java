package com.lusuoria.settlement.dto.response;

import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 项目订单响应 DTO
 *
 * 敏感字段（收入、利润、提成）：
 *   - 仅 ADMIN 和 AUDITOR 可见
 *   - STAFF / GUEST 返回时这些字段为 null，前端也不展示
 *
 * 非敏感字段（应付金额 payableAmount 等）不受限制
 */
@Data
public class ProjectOrderResponse {

    private Long id;
    private String internalProjectNo;
    private String clientOrderNo;
    private String projectMonth;
    private ProjectType projectType;
    private String projectTypeLabel;
    private String cooperationContent;
    private Integer cooperationQuantity;
    private Boolean isOwnResource;

    // 关联
    private Long brandId;
    private String brandName;
    private Long influencerId;
    private String influencerTeam;
    private String influencerAccount;
    private Long projectManagerId;
    private String projectManagerName;

    // ===== 非敏感成本字段（所有角色可见）=====
    private BigDecimal clientUnitPrice;      // 客户单价
    private String currency;
    private BigDecimal exchangeRate;
    private BigDecimal influencerUnitPrice;  // 红人单价
    private BigDecimal influencerCost;       // 红人成本

    // ===== 敏感字段（ADMIN / AUDITOR 才能看到，其他角色返回 null）=====
    private BigDecimal clientRevenue;        // 客户收入
    private BigDecimal rmbRevenue;           // 人民币收入
    private BigDecimal otherExternalCost;    // 其他外部成本
    private BigDecimal internalExecutionCost;// 内部执行成本
    private BigDecimal grossProfit;          // 项目毛利
    private BigDecimal distributableProfit;  // 可分配利润
    private BigDecimal commissionRate;       // 提成比例
    private BigDecimal commissionAmount;     // 提成金额
    private BigDecimal companyNetProfit;     // 公司利润
    // ===== 敏感字段结束 =====

    // 甲方状态（所有人可见）
    private ClientStatus clientStatus;
    private String clientStatusLabel;
    private Boolean contractSigned;
    private Date expectedReceiptDate;
    private Date actualReceiptDate;
    private BigDecimal receivedAmount;       // 已到账金额（所有人可见）

    // 内部状态（所有人可见）
    private InternalSettlementStatus internalStatus;
    private String internalStatusLabel;

    private String notes;
    private Date createdAt;
    private Date updatedAt;
}
