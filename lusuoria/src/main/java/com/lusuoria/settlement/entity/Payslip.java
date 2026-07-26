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
 * confirmed=true 时，上述快照字段是"确认"那一刻算出来并冻结的，之后不会再变，即使
 * 后续合作跟踪数据被修改；取消确认（confirmed 改回 false）不会清空这些快照字段，
 * 只是不再被使用，下次重新确认会覆盖。
 *
 * (employeeId, yearMonth) 唯一性由 PayslipService 在 service 层"先查（含软删）后插/复用"
 * 保证，不建数据库唯一约束（跟 DomainCache.getOrCreate 的既有套路一致）。
 */
@Entity
@Table(name = "payslips")
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

    @Column(name = "confirmed", nullable = false)
    private Boolean confirmed;

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
