package com.lusuoria.settlement.enums;

/**
 * 进度提醒类别（2026-07 新增，跑批生成，展示在"待处理-进度提醒"里）。
 * 预留扩展空间：以后新增其他类型的提醒跑批，直接加新的枚举值即可，不需要改表结构。
 *
 *   COLLAB_PAYMENT_DUE          - 红人合作跟踪临近结款提醒，覆盖按红人成本阈值分档、月结两种
 *                                 品牌方付款周期（2026-08 起月结品牌方也纳入，按单条记录处理，
 *                                 用"视频发布月份最后一个工作日+对账日后N天"模拟结款截止日；
 *                                 原来单独的 BRAND_MONTH_END_PAYMENT_DUE 类别——按品牌方+月份
 *                                 汇总、不排除已结部分、无下钻明细——已被这次改动取代并删除）
 *   INFLUENCER_PAYMENT_DUE      - 红人结款临近付款日提醒（"红人结款"状态=待付款，按预计付款日
 *                                 距今天数分档，档位口径完全同 COLLAB_PAYMENT_DUE）
 *   PM_EXECUTOR_PROGRESS_STALL  - 项目负责人/执行人员视角：视频项目进度长时间未流转
 *   FINANCE_PROGRESS_STALL      - 财务视角：视频项目进度长时间未流转（已发布未结算/已加入客户
 *                                 未结算列表迟迟没到客户已结算）
 *   REQUIREMENT_INVOICE_OVERDUE  - 需求完成后长时间未上传 Invoice
 *   REQUIREMENT_CONTRACT_OVERDUE - 需求完成后长时间未上传合同（仅品牌方/团队"每次需求签一次合同"）
 *   CONTRACT_EXPIRING_SOON       - 合同即将到期/已过期（仅品牌方/团队"一年签一次合同"，按红人个人
 *                                  合同优先、团队兜底默认有效期次之判断当前生效合同的到期时间）
 *   INFLUENCER_PAYMENT_RECEIPT_OVERDUE - 红人结款上传发票逾期（仅"涉及公对公发票"的品牌方-团队
 *                                  组合，付款状态=已付款后长时间未上传发票，阈值口径跟
 *                                  REQUIREMENT_CONTRACT_OVERDUE 一致）
 *   DELETE_REQUEST_PENDING       - 存在未处理的"删除审核"（PendingApprovalCategory.DELETE_REQUEST）
 *                                  申请（2026-08-16 新增）。只有 SysUser.role=ADMIN 的登录账号
 *                                  能审核，受众按 ADMIN_ONLY_CATEGORIES 单独收窄（不是按"管理层"
 *                                  员工角色），不分档（存在未处理就提醒，不管放了多久）。
 *   PROGRESS_ROLLBACK_PENDING    - 存在未处理的"视频项目进度倒退审核"（PendingApprovalCategory.
 *                                  PROGRESS_ROLLBACK）申请（2026-08-16 新增）。跟 DELETE_REQUEST_PENDING
 *                                  同一套受众规则（ADMIN 专属）、同样不分档。
 *   EXECUTOR_COST_MODIFY_PENDING - 存在未处理的"内部执行成本修改审核"（PendingApprovalCategory.
 *                                  EXECUTOR_COST_MODIFY）申请（2026-08-16 新增）。审核人是该记录的
 *                                  项目负责人本人（不是 ADMIN），按目标项目负责人（audienceEmployeeId）
 *                                  定向生成，走跟 PM_EXECUTOR_PROGRESS_STALL 一样的
 *                                  EMPLOYEE_OWNED_CATEGORIES 受众机制（管理层/ADMIN 也能整体看到），
 *                                  不分档。
 */
public enum ReminderCategory {
    COLLAB_PAYMENT_DUE("红人合作跟踪临近结款"),
    INFLUENCER_PAYMENT_DUE("红人结款临近付款日"),
    PM_EXECUTOR_PROGRESS_STALL("进度滞留-项目"),
    FINANCE_PROGRESS_STALL("进度滞留-财务"),
    REQUIREMENT_INVOICE_OVERDUE("Invoice逾期"),
    REQUIREMENT_CONTRACT_OVERDUE("合同上传逾期"),
    CONTRACT_EXPIRING_SOON("合同即将到期"),
    INFLUENCER_PAYMENT_RECEIPT_OVERDUE("红人结款上传发票逾期"),
    DELETE_REQUEST_PENDING("删除审核待处理"),
    PROGRESS_ROLLBACK_PENDING("进度倒退审核待处理"),
    EXECUTOR_COST_MODIFY_PENDING("执行成本修改审核待处理");

    private final String label;

    ReminderCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
