package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;
import java.util.Date;

/**
 * 项目负责人对"应发给自己名下执行人员的工资"的独立确认——三层薪酬关系
 * （管理层→项目负责人→执行人员）里"项目负责人→执行人员"这一层，由项目负责人自己操作，
 * 跟管理层对这个项目负责人自己工资单（{@link Payslip}）的确认/取消确认完全独立，
 * 互不影响、互不阻塞。
 *
 * 2026-07 起粒度改成"每个执行人员单独确认"（此前是一个项目负责人当月名下所有执行人员
 * 一次性打包确认，用户反馈这样不够精细：项目负责人应该能对某几个执行人员先确认、其余
 * 继续按实时数据展示），所以 (managerId, executorId, yearMonth) 三者共同唯一确定一条记录，
 * 不再是 (managerId, yearMonth) 两者。这次改造之前产生的历史记录 executorId 是 NULL，
 * 新逻辑下查不到、也不会再被使用，等同于自然失效，不需要手动清理。
 *
 * confirmed=false（草稿/预览态）时 detailJson 不使用，前端展示的是 PayslipService
 * 实时算出来的"预计"；confirmed=true 时 detailJson 是确认那一刻冻结的快照，之后不会
 * 再变，即使后续合作跟踪数据被修改。取消确认（confirmed 改回 false）不会清空 detailJson，
 * 下次重新确认会覆盖，跟 Payslip 的确认/取消确认是同一套约定。
 *
 * (managerId, executorId, yearMonth) 唯一性由 PayslipService 在 service 层"先查后插/复用"
 * 保证，不建数据库唯一约束（同项目里既有的惯例）。
 */
@Entity
@Table(name = "executor_wage_confirmations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutorWageConfirmation extends BaseEntity {

    /** 项目负责人（也可能是以项目负责人身份行事的管理层）的员工 id */
    @Column(name = "manager_id", nullable = false)
    private Long managerId;

    /** 被确认工资的执行人员员工 id（2026-07 新增，改成按执行人员单独确认后必填） */
    @Column(name = "executor_id")
    private Long executorId;

    /** yyyyMM */
    @Column(name = "year_month", nullable = false)
    private String yearMonth;

    @Column(name = "confirmed", nullable = false)
    private Boolean confirmed;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "confirmed_at")
    private Date confirmedAt;

    /**
     * 确认时冻结的快照：序列化的 List&lt;PayslipDimensionRow&gt;，这个执行人员在这个项目负责人
     * 名下当月的明细行 + 一行小计（不再像改造前那样把该项目负责人名下所有执行人员打包存一份，
     * 现在一份快照只对应一个执行人员），直接复用 PayslipDetailResponse 里同一个行结构。
     */
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;
}
