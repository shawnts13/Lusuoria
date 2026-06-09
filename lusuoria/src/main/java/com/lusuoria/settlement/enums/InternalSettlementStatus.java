package com.lusuoria.settlement.enums;

/**
 * 内部结算状态
 */
public enum InternalSettlementStatus {
    PENDING_CALC("待核算"),
    CALCULATED("已核算"),
    PENDING_APPROVAL("待老板审核"),
    CONFIRMED("已确认"),
    IN_PAYROLL("已进入工资"),
    ARCHIVED("已归档");

    private final String label;
    InternalSettlementStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}