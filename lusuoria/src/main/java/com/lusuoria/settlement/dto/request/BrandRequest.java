package com.lusuoria.settlement.dto.request;

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
}