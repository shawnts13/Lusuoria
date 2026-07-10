package com.lusuoria.settlement.enums;

/**
 * 进度提醒类别（2026-07 新增，跑批生成，展示在"待处理-进度提醒"里）。
 * 预留扩展空间：以后新增其他类型的提醒跑批，直接加新的枚举值即可，不需要改表结构。
 *
 *   COLLAB_PAYMENT_DUE          - 红人合作跟踪临近结款提醒（品牌方付款周期=按红人成本阈值分档）
 *   BRAND_MONTH_END_PAYMENT_DUE - 品牌方月结临近结款提醒（品牌方付款周期=月底对账日后N天结款）
 */
public enum ReminderCategory {
    COLLAB_PAYMENT_DUE("红人合作跟踪临近结款"),
    BRAND_MONTH_END_PAYMENT_DUE("品牌方月结临近结款");

    private final String label;

    ReminderCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
