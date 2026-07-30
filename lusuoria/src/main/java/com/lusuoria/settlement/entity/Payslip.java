package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 工资单：一个员工在某个月份的一份工资单记录。
 *
 * confirmed=false（草稿/预览态）时，detailJson/exchangeRateSnapshot 这些"确认时冻结的快照"
 * 字段不使用，前端展示的是 PayslipService 实时算出来的"工资单预计"；只有 extraBonusAmount/
 * extraBonusCurrency（奖金）和 legalSalaryRmb（法务工资）是随时手动维护、随时生效的草稿值，
 * 不受 confirmed 状态影响是否被"使用"，只受 confirmed 状态影响能不能编辑
 * （confirmed=true 时必须先取消确认才能改，见 PayslipService）。
 *
 * finalConfirmed=true 时（见该字段注释），上述快照字段是最后一次判定"最终版"那一刻算出来并
 * 冻结的，之后不会再变，即使后续合作跟踪数据被修改；取消确认（confirmed 改回 false）会连带把
 * finalConfirmed 也改回 false，但不清空这些快照字段，只是不再被使用，下次重新到达最终版会覆盖。
 *
 * (employeeId, yearMonth) 唯一性由 PayslipService 在 service 层"先查（含软删）后插/复用"
 * 保证，不建数据库唯一约束（跟 DomainCache.getOrCreate 的既有套路一致）。
 */
@Entity
@Table(name = "payslips", indexes = {
        // 覆盖 findByYearMonthAndIsDeletedFalse（列表页按月查全部）和
        // findByEmployeeIdAndYearMonthAndIsDeletedFalse（单个员工按月查）两种查询
        @Index(name = "idx_payslip_year_month_employee", columnList = "year_month, employee_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payslip extends BaseEntity {

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** yyyyMM */
    @Column(name = "year_month", nullable = false)
    private String yearMonth;

    /** 员工角色快照：草稿态每次读取都会刷新，确认时冻结 */
    @Column(name = "employee_role")
    private String employeeRole;

    /** 管理层是否已经点过这份工资单的"确认"（自己那部分：奖金/提成，含冻结前的编辑锁）*/
    @Column(name = "confirmed", nullable = false)
    private Boolean confirmed;

    /**
     * 是否"最终版"（2026-07-28 新增）：项目负责人/执行人员这两个角色，除了 {@link #confirmed}
     * 之外，还需要相关的 ExecutorWageConfirmation（"手下执行人员工资"/"执行人员工资"）全部
     * 确认到位，才会变 true；其余角色（财务/IT后勤/法务/管理层自己）没有这层下游依赖，
     * 恒等于 confirmed。只有 finalConfirmed=true 时，detailJson/exchangeRateSnapshot 才是真正
     * 冻结、之后不再变化的快照——confirmed=true 但 finalConfirmed=false 期间，展示的仍是实时
     * 计算的数据（见 PayslipService.recomputeFinality/resolveDisplay）。
     *
     * 故意不加 nullable=false：这是新增到已有数据的表上的列，ddl-auto=update 生成的
     * ADD COLUMN 语句没有 DEFAULT，如果标 NOT NULL，Postgres 会因为已有行而报错拒绝这次自动
     * 建表变更。全部读取都经过 Boolean.TRUE.equals(...)，不会因为 null 报错——但历史行的
     * finalConfirmed 会是 null（=按 false 处理），已经"确认"过的历史月份工资单会在这次上线后
     * 短暂显示成"预计/待确认"而不是"已确认"，直到下次被摸到（确认/取消确认等动作）才会重新
     * 判定。需要部署后手动跑一次性回填（见 Shawn 沟通记录里给的 SQL），把历史行的
     * final_confirmed 直接对齐 confirmed，避免历史数据在 UI 上倒退。
     */
    @Column(name = "final_confirmed")
    private Boolean finalConfirmed;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "confirmed_at")
    private Date confirmedAt;

    /** 操作确认的管理层账号对应的员工 id */
    @Column(name = "confirmed_by_employee_id")
    private Long confirmedByEmployeeId;

    // ===== 管理层手动维护，草稿态可编辑，确认后锁定 =====

    /** "奖金"：跟提成/薪酬/阶梯bonus都独立的月度额外奖励，任何角色通用，可空=未设置 */
    @Column(name = "extra_bonus_amount", precision = 15, scale = 2)
    private BigDecimal extraBonusAmount;

    /** "USD"/"RMB"，配合 extraBonusAmount */
    @Column(name = "extra_bonus_currency")
    private String extraBonusCurrency;

    /** 法务专用：管理层手动输入的本月工资（人民币） */
    @Column(name = "legal_salary_rmb", precision = 15, scale = 2)
    private BigDecimal legalSalaryRmb;

    // ===== 确认时冻结的快照 =====

    /**
     * 确认时冻结的完整明细快照（序列化自 PayslipDetailResponse，见该类注释）——包括维度明细行、
     * 提成比例、提成金额/执行薪酬合计/固定月薪/法务工资、阶梯Bonus、以及管理层专属的
     * 项目毛利/可分配利润/负责人提成合计/内部执行人力成本/内部其他员工成本/公司利润，
     * 所有金额都是"原始计价币种"（未做汇率换算）。总工资不落在这个快照里——它是每次读取时
     * 用（已经换算成请求币种的）各组成部分现算现加，避免"总工资"跟"组成部分之和"因为
     * 分别取整独立换算而对不上。
     */
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    /** 确认时刻使用的美元兑人民币汇率 */
    @Column(name = "exchange_rate_snapshot", precision = 10, scale = 4)
    private BigDecimal exchangeRateSnapshot;
}
