package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ContractLinkRequest {
    @NotBlank(message = "请填写合同链接")
    private String contractLink;
}
