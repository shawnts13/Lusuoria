package com.lusuoria.settlement.enums;

/**
 * 红人合作跟踪 - 红人结款进度
 *
 * 默认为空。只有当"视频项目进度"达到 {@link CollaborationProgress#allowsPaymentProgress()}
 * 要求的三个阶段（已发布(未结算)/已加入客户未结算列表/客户已结算）时，才允许设置这个字段的值
 * （由 CollaborationTrackingService 在保存/状态流转时统一校验，Excel 导入同样校验）。
 *
 * INCLUDED_IN_PAYMENT_BATCH / INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE 这两个值只能由
 * InfluencerPaymentService 在红人结款纳入/移出批次时内部设置（直接操作实体，不走这里的校验），
 * 普通保存/状态流转/Excel导入都不允许手动选中这两个值——2026-07 起统一拒绝，
 * 见 isSystemManagedOnly()。
 */
public enum InfluencerPaymentProgress {
    PENDING_INVOICE("待红人发送invoice"),
    INVOICE_PROVIDED("红人已提供invoice"),
    PENDING_SETTLEMENT_NO_INVOICE("待结款（不涉及invoice）"),
    INCLUDED_IN_PAYMENT_BATCH("已纳入红人结款批次"),
    /** 纳入结款批次时，原状态是"待红人发送invoice"——保留这个信息，不能让原状态被悄悄抹掉 */
    INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE("已纳入红人结款批次（缺少invoice）");

    /** 前置条件不满足时的统一报错文案，保存校验/Excel导入校验共用同一句话 */
    public static final String PRECONDITION_ERROR = "红人结款进度设置失败，因为视频项目进度未达到前置要求";

    /** 手动设置这两个值时的统一报错文案 */
    public static final String SYSTEM_MANAGED_ERROR = "此状态仅能由管理层通过\"红人结款\"功能设置";

    private final String label;

    InfluencerPaymentProgress(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否已纳入结款批次（不管纳入时原状态是不是缺 invoice），候选查询/前置校验common用 */
    public boolean isIncludedInBatch() {
        return this == INCLUDED_IN_PAYMENT_BATCH || this == INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE;
    }

    /** 是否只能由系统（红人结款模块）内部设置，不能通过普通保存/状态流转/Excel导入手动选中 */
    public boolean isSystemManagedOnly() {
        return isIncludedInBatch();
    }

    /** 根据中文标签反查枚举（Excel 导入用） */
    public static InfluencerPaymentProgress fromLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        for (InfluencerPaymentProgress p : values()) {
            if (p.label.equals(trimmed)) return p;
        }
        return null;
    }
}
