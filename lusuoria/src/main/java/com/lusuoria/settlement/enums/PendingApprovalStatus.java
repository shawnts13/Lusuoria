package com.lusuoria.settlement.enums;

public enum PendingApprovalStatus {
    PENDING("待审核"),
    APPROVED("已同意"),
    REJECTED("已拒绝");

    private final String label;
    PendingApprovalStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
