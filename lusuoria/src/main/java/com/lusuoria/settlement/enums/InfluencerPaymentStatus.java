package com.lusuoria.settlement.enums;

/**
 * 红人结款 - 付款状态
 */
public enum InfluencerPaymentStatus {
    PENDING("待付款"),
    PAID("已付款");

    private final String label;
    InfluencerPaymentStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
