package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class InfluencerPaymentRequest {
    private Long id;

    @NotBlank(message = "结算月份不能为空")
    private String settlementMonth;

    @NotNull(message = "红人不能为空")
    private Long influencerId;

    private String cooperationContent;
    private Integer cooperationQuantity;
    private BigDecimal influencerUnitPrice;
    private BigDecimal payableAmount;
    private String currency;
    private BigDecimal exchangeRate;
    private BigDecimal rmbAmount;
    private Date reconcileDate;
    private Date expectedPaymentDate;
    private Date actualPaymentDate;
    private InfluencerPaymentStatus paymentStatus;
    private BigDecimal paidAmount;
    private String notes;
}