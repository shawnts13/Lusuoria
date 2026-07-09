package com.lusuoria.settlement.enums;

/**
 * 品牌方付款周期类型（2026-07 起，为红人结款功能建设铺路）
 *
 *   COST_THRESHOLD - 按红人成本阈值分档：单笔"红人视频制作与发布成本"（结算币种）
 *                    <= 阈值 时按一个天数结款，> 阈值 时按另一个天数结款
 *                    （具体阈值/天数见 Brand.costThresholdAmount / daysWithinThreshold / daysAboveThreshold）
 *   MONTH_END      - 月结：月底对账日后固定天数内结款（见 Brand.daysAfterMonthEnd）
 */
public enum PaymentCycleType {
    COST_THRESHOLD("按红人成本阈值分档"),
    MONTH_END("月底对账日后N天结款");

    private final String label;

    PaymentCycleType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 根据中文标签反查枚举（Excel 导入用） */
    public static PaymentCycleType fromLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        for (PaymentCycleType t : values()) {
            if (t.label.equals(trimmed)) return t;
        }
        return null;
    }
}
