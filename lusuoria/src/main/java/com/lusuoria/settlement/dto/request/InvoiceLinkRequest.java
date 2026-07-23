package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class InvoiceLinkRequest {
    @NotBlank(message = "请填写Invoice链接")
    private String invoiceLink;
}
