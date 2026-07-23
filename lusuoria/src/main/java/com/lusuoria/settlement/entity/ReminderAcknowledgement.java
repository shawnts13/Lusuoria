package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.ReminderCategory;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.util.Date;

/**
 * 提醒"标记已处理"（2026-07 新增，供 PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL/
 * REQUIREMENT_INVOICE_OVERDUE 这3类"项目流转"相关提醒用，管理层视角的老两类"临近结款"提醒
 * 不涉及）。
 *
 * 跟 progress_reminders/progress_reminder_details 完全独立、永不被那两张表的批次重建清空——
 * 那两张表是"当次跑批的快照"，每天/每次手动重算都会整体删了重插；这张表是"谁在什么时候确认
 * 处理过哪条业务记录"的持久记录，只在 acknowledge() 时写入，从不被批次逻辑删除。
 *
 * 失效判定：只有当 snapshotChangedAt（标记那一刻业务记录的 progressChangedAt/completedAt）
 * 仍然等于业务记录当前的实时值时，这条"已处理"标记才生效（过滤掉对应的提醒明细）；一旦业务
 * 记录的时间戳真的往前走了（说明确实推进了），这条标记就自动失效，不再生效——不需要手动清理。
 */
@Entity
@Table(name = "reminder_acknowledgements")
@Getter
@Setter
public class ReminderAcknowledgement extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private ReminderCategory category;

    /** 业务记录 id：PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL 是 trackingId，
     * REQUIREMENT_INVOICE_OVERDUE 是 requirementId */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 标记人（员工 id），只影响这个人自己后续看不看得到这条提醒，不影响其他人 */
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** 标记那一刻业务记录的 progressChangedAt（tracking）或 completedAt（requirement）快照 */
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "snapshot_changed_at")
    private Date snapshotChangedAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "acknowledged_at", nullable = false)
    private Date acknowledgedAt;
}
