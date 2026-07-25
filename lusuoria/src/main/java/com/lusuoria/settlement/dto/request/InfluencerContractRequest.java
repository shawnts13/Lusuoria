package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class InfluencerContractRequest {
    @NotNull(message = "红人不能为空")
    private Long influencerId;

    @NotNull(message = "请选择合同年份")
    private Integer year;

    @NotBlank(message = "请填写合同链接")
    private String contractLink;
}
