package com.lusuoria.settlement.enums;

/**
 * 红人需求管理 - "结款状态"（2026-08 新增）。
 *
 * 没有值（null，前端展示"-"）代表还没有任何红人结款记录关联到这个需求下的合作跟踪记录——
 * 不管这个需求"需求完成进度"是不是100%都可能是这个状态（100%完成但还没被"红人结款"模块
 * 添加进任何批次，正是"查看未结款的需求"这个筛选按钮要找出来的情况）。
 *
 * 由 InfluencerRequirementService.refreshSettlementStatus() 统一维护，在红人结款记录
 * 新建/编辑（调整了勾选的红人视频项目）/状态流转/删除这几个时机由 InfluencerPaymentService
 * 调用同步，不要在别处直接设置。
 */
public enum RequirementSettlementStatus {
    ADDED_TO_PAYMENT("已添加结款记录"),
    PAID("已付款");

    private final String label;

    RequirementSettlementStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
