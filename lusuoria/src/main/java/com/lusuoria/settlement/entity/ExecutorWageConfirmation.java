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
 * confirmed=false（草稿/预览态）时 detailJson 不使用，前端展示的是 PayslipService
 * 实时算出来的"预计"；confirmed=true 时 detailJson 是确认那一刻冻结的快照，之后不会
 * 再变，即使后续合作跟踪数据被修改。取消确认（confirmed 改回 false）不会清空 detailJson，
 * 下次重新确认会覆盖，跟 Payslip 的确认/取消确认是同一套约定。
 *
 * (managerId, yearMonth) 唯一性由 PayslipService 在 service 层"先查后插/复用"保证，
 * 不建数据库唯一约束（同项目里既有的惯例）。
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

    /** yyyyMM */
    @Column(name = "year_month", nullable = false)
    private String yearMonth;

    @Column(name = "confirmed", nullable = false)
    private Boolean confirmed;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "confirmed_at")
    private Date confirmedAt;

    /**
     * 确认时冻结的快照：序列化的 List&lt;PayslipDimensionRow&gt;，按执行人员分组
     * （每个执行人员一组明细行 + 一行小计，最后一行整体汇总），直接复用 PayslipDetailResponse
     * 里同一个行结构，不用另外设计一套 DTO。
     */
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;
}
