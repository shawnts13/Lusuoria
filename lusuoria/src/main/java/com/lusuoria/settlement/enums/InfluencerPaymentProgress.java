package com.lusuoria.settlement.enums;

/**
 * 红人合作跟踪 - 红人结款进度
 *
 * 默认为空。只有当"视频项目进度"达到 {@link CollaborationProgress#allowsPaymentProgress()}
 * 要求的三个阶段（已发布(未结算)/已加入客户未结算列表/客户已结算）时，才允许设置这个字段的值
 * （由 CollaborationTrackingService 在保存/状态流转时统一校验，Excel 导入同样校验）。
 */
public enum InfluencerPaymentProgress {
    PENDING_INVOICE("待红人发送invoice"),
    INVOICE_PROVIDED("红人已提供invoice"),
    PENDING_SETTLEMENT_NO_INVOICE("待结款（不涉及invoice）"),
    INCLUDED_IN_PAYMENT_BATCH("已纳入红人结款批次");

    /** 前置条件不满足时的统一报错文案，保存校验/Excel导入校验共用同一句话 */
    public static final String PRECONDITION_ERROR = "红人结款进度设置失败，因为视频项目进度未达到前置要求";

    private final String label;

    InfluencerPaymentProgress(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
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
