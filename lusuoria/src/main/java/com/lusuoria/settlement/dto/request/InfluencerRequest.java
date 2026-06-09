package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.ProjectType;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class InfluencerRequest {
    private Long id;
    @NotNull(message = "红人类型不能为空")
    private ProjectType influencerType;
    private String teamName;
    @NotBlank(message = "红人账号不能为空")
    private String accountName;
    private String countryMarket;
    private String platform;
    private String cooperationMode;
    private String paymentInfo;
    private String notes;
}