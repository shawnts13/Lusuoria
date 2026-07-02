package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.PendingApprovalStatus;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.util.Date;

/**
 * 待处理事项。目前唯一的类别是"删除审核"：
 * STAFF/ADMIN 对项目订单/红人合作跟踪发起删除时，不直接删除，而是生成一条待审核记录，
 * 由 ADMIN 在"待处理"模块里查看详情后选择同意（真正执行删除）或拒绝（记录原样保留）。
 *
 * category 字段预留了扩展空间，后续可以加其他类型的提醒事项（比如员工确认工资条），
 * 不需要改动这张表的结构。
 */
@Entity
@Table(name = "pending_approvals")
@Getter
@Setter
public class PendingApproval extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private PendingApprovalCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_module", nullable = false)
    private PendingApprovalModule targetModule;

    /** 目标记录的 id（项目订单 id 或 红人合作跟踪 id） */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 目标记录的内部项目编号，冗余存一份方便列表展示和跳转查看详情 */
    @Column(name = "target_internal_project_no")
    private String targetInternalProjectNo;

    /** 目标记录的简要描述，比如"品牌方 - 红人账号名"，方便一眼看出是什么 */
    @Column(name = "target_summary", length = 300)
    private String targetSummary;

    /** 发起人（登录用户名） */
    @Column(name = "requested_by")
    private String requestedBy;

    /** 删除原因 */
    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PendingApprovalStatus status = PendingApprovalStatus.PENDING;

    /** 审核人（登录用户名） */
    @Column(name = "resolved_by")
    private String resolvedBy;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "resolved_at")
    private Date resolvedAt;

    /** 拒绝原因（同意时不需要填） */
    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;
}
