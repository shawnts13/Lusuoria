package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class ExchangeRateRequest {

    /** 业务月份，格式 202606 */
    @NotBlank(message = "月份不能为空")
    private String yearMonth;

    /** 1 美元 = 多少人民币 */
    @NotNull(message = "汇率不能为空")
    private BigDecimal usdToCny;
}
