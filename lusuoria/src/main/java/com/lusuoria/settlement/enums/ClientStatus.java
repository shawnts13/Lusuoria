package com.lusuoria.settlement.enums;

public enum ClientStatus {
    PENDING_SUBMIT("待提交"),
    SUBMITTED("已提交"),
    CLIENT_CONFIRMED("甲方已确认"),
    CLIENT_RECONCILED("甲方已对账"),
    CONTRACT_SIGNED("合同已签署"),
    PENDING_PAYMENT("待到账"),
    PARTIAL_PAYMENT("部分到账"),
    PAID("已到账"),
    ABNORMAL("异常");

    private final String label;

    ClientStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}