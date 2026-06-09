package com.lusuoria.settlement.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class BrandRequest {
    private Long id;
    @NotBlank(message = "品牌方名称不能为空")
    private String name;
    private String countryMarket;
    private String cooperationType;
    private String contactPerson;
    private String settlementCurrency;
    private String paymentCycle;
    private String notes;
}