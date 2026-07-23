package com.lusuoria.settlement.enums;

/**
 * 进度提醒类别（2026-07 新增，跑批生成，展示在"待处理-进度提醒"里）。
 * 预留扩展空间：以后新增其他类型的提醒跑批，直接加新的枚举值即可，不需要改表结构。
 *
 *   COLLAB_PAYMENT_DUE          - 红人合作跟踪临近结款提醒（品牌方付款周期=按红人成本阈值分档）
 *   BRAND_MONTH_END_PAYMENT_DUE - 品牌方月结临近结款提醒（品牌方付款周期=月底对账日后N天结款）
 *   PM_EXECUTOR_PROGRESS_STALL  - 项目负责人/执行人员视角：视频项目进度长时间未流转
 *   FINANCE_PROGRESS_STALL      - 财务视角：视频项目进度长时间未流转（已发布未结算/已加入客户
 *                                 未结算列表迟迟没到客户已结算）
 *   REQUIREMENT_INVOICE_OVERDUE - 需求完成后长时间未上传 Invoice
 */
public enum ReminderCategory {
    COLLAB_PAYMENT_DUE("红人合作跟踪临近结款"),
    BRAND_MONTH_END_PAYMENT_DUE("品牌方月结临近结款"),
    PM_EXECUTOR_PROGRESS_STALL("进度滞留-项目"),
    FINANCE_PROGRESS_STALL("进度滞留-财务"),
    REQUIREMENT_INVOICE_OVERDUE("Invoice逾期");

    private final String label;

    ReminderCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
