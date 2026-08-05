package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** "红人结款"上传公对公发票用（2026-08 新增），跟 InvoiceLinkRequest 是同一套机制，命名规则用 receipt */
@Data
public class ReceiptLinkRequest {
    @NotBlank(message = "请填写发票链接")
    private String receiptLink;
}
