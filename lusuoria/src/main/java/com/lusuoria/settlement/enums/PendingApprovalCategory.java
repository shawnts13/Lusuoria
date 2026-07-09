package com.lusuoria.settlement.enums;

/**
 * 待处理事项类别。
 * DELETE_REQUEST：删除审核（项目订单/红人合作跟踪的删除都要走这个）。
 * PROGRESS_ROLLBACK：红人合作跟踪"视频项目进度"倒退审核——当"红人结款进度"已经填了值，
 * 又要把"视频项目进度"改回不满足前置条件的状态时，不能直接生效，要走管理员审核
 * （跟删除审核是完全一样的"发起申请 -> 管理员在待处理里同意/拒绝"模式）。
 */
public enum PendingApprovalCategory {
    DELETE_REQUEST("删除审核"),
    PROGRESS_ROLLBACK("视频项目进度倒退审核");

    private final String label;
    PendingApprovalCategory(String label) { this.label = label; }
    public String getLabel() { return label; }
}
