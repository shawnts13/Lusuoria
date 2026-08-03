package com.lusuoria.settlement.enums;

/**
 * 品牌方付款周期类型（2026-07 起，为红人结款功能建设铺路）
 *
 *   COST_THRESHOLD - 按红人成本阈值分档：成本（结算币种）<= 阈值 时按一个天数结款，> 阈值 时
 *                    按另一个天数结款；成本口径 2026-08 起区分品牌方是否需要invoice——需要
 *                    invoice 的按"单个需求实际可结款成本"（不含"折损"条目），不需要的按
 *                    "单笔成本"，精确定义见 Brand.costThresholdAmount，不要在这里假设是
 *                    InfluencerRequirement.totalInfluencerCost（那是计划总成本，口径不同）
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
