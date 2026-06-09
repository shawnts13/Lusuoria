package com.lusuoria.settlement.enums;

/**
 * 红人侧状态
 */
public enum InfluencerPaymentStatus {
    PENDING_RECONCILE("待对账"),
    RECONCILED("已对账"),
    PENDING_PAYMENT("待付款"),
    PARTIAL_PAYMENT("部分付款"),
    PAID("已付款"),
    ABNORMAL("异常");

    private final String label;
    InfluencerPaymentStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}