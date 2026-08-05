package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.ContractCycleType;
import com.lusuoria.settlement.enums.PaymentCycleType;
import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class BrandRequest {
    private Long id;
    @NotBlank(message = "品牌方名称不能为空")
    private String name;
    private String countryMarket;
    private String contactPerson;
    private String settlementCurrency;

    private PaymentCycleType paymentCycleType;
    private java.math.BigDecimal costThresholdAmount;
    private Integer daysWithinThreshold;
    private Integer daysAboveThreshold;
    private Integer daysAfterMonthEnd;

    private String notes;

    private Boolean requiresInvoice;
    private ContractCycleType contractCycleType;

    /** 是否涉及"客户方的项目订单"（2026-08 新增），null 按"涉及"处理 */
    private Boolean involvesClientOrderId;
    /** 是否涉及"客户方付款批次"（2026-08 新增），null 按"涉及"处理 */
    private Boolean involvesClientPaymentBatch;

    /** "红人结款"上传公对公发票默认值（2026-08 新增），null 按"不涉及"处理（跟上面几个字段方向相反） */
    private Boolean defaultInvolvesCorporateInvoice;
}