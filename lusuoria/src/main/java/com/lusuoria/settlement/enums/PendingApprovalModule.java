package com.lusuoria.settlement.enums;

/**
 * 待处理事项所属的业务模块（目标记录属于哪个模块）
 *
 * 2026-07："项目订单"模块整体废弃，PROJECT_ORDER 这个值一并移除。
 * 注意：移除前必须先清空 pending_approvals 表里 target_module='PROJECT_ORDER' 的历史记录
 * （见部署脚本 01-migrate-project-order-fields.sql 最后一步），否则 Hibernate
 * 反序列化这些历史行时会因为找不到对应的枚举常量而报错。
 */
public enum PendingApprovalModule {
    COLLABORATION_TRACKING("红人合作跟踪");

    private final String label;
    PendingApprovalModule(String label) { this.label = label; }
    public String getLabel() { return label; }
}
