package com.lusuoria.settlement.enums;

/**
 * 红人合作跟踪 - 红人结款进度
 *
 * 2026-08 起完全由系统控制，任何入口（单条新建/编辑表单、状态流转弹窗、Excel 导入）都不再
 * 接受手动指定这个字段的值——前端表单已经把这个下拉框整个去掉，Excel 模板也去掉了这一列。
 * 默认为空，只有当"视频项目进度"达到 {@link CollaborationProgress#allowsPaymentProgress()}
 * 要求的三个阶段（已发布(未结算)/已加入客户未结算列表/客户已结算）时才会有值，具体赋值/更新
 * 全部由 CollaborationTrackingService（doSave() 的自动补值分支 + updateStatus() 里"首次进入
 * 已发布(未结算)"）、InfluencerRequirementService（上传 invoice 联动）、
 * InfluencerPaymentService（纳入/移出红人结款批次）这几处系统内部逻辑触发。
 *
 * INCLUDED_IN_PAYMENT_BATCH / INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE 这两个值只能由
 * InfluencerPaymentService 在红人结款纳入/移出批次时内部设置（直接操作实体），
 * 见 isSystemManagedOnly()（现在仅供 updateStatus() 内部的进度倒退判断使用）。
 */
public enum InfluencerPaymentProgress {
    PENDING_INVOICE("待红人发送invoice"),
    INVOICE_PROVIDED("红人已提供invoice"),
    PENDING_SETTLEMENT_NO_INVOICE("待结款（不涉及invoice）"),
    INCLUDED_IN_PAYMENT_BATCH("已纳入红人结款批次"),
    /** 纳入结款批次时，原状态是"待红人发送invoice"——保留这个信息，不能让原状态被悄悄抹掉 */
    INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE("已纳入红人结款批次（缺少invoice）");

    /** 手动设置这两个值时的统一报错文案（updateStatus() 里进度倒退判断仍会用到） */
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

    /** 是否只能由系统（红人结款模块）内部设置 */
    public boolean isSystemManagedOnly() {
        return isIncludedInBatch();
    }
}
