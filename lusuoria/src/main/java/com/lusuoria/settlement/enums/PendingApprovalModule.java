package com.lusuoria.settlement.enums;

/**
 * 待处理事项所属的业务模块（目标记录属于哪个模块）
 */
public enum PendingApprovalModule {
    PROJECT_ORDER("项目订单"),
    COLLABORATION_TRACKING("红人合作跟踪");

    private final String label;
    PendingApprovalModule(String label) { this.label = label; }
    public String getLabel() { return label; }
}
