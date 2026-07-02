package com.lusuoria.settlement.enums;

/**
 * 待处理事项类别。目前只有"删除审核"一种，后续可以扩展其他提醒类型
 * （比如提醒员工确认工资条），不需要改动"待处理"模块本身的框架。
 */
public enum PendingApprovalCategory {
    DELETE_REQUEST("删除审核");

    private final String label;
    PendingApprovalCategory(String label) { this.label = label; }
    public String getLabel() { return label; }
}
